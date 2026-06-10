package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.Border

/**
 * Expandable panel for a single preset. Shows the preset name, main repo combo,
 * and one [SubRow] per submodule. Branch lists are loaded lazily on first expand
 * via [scope] coroutines.
 *
 * State tracking:
 * - [loadedOnce]: whether the branch combo lists have been loaded (lazy, on first expand)
 * - [loadingCount]: number of in-flight async branch loads; [updateDirty] suppresses
 *   the dirty check while any load is pending
 * - [initializing]: true during constructor; prevents false dirty flags during setup
 */
class PresetEditor(
    private val gitRoot: Path,
    initial: Preset,
    private val log: (String) -> Unit,
    private val onSwitch: (Preset) -> Unit,
    private val onSave: (Preset) -> Unit,
    private val onDelete: () -> Unit,
    private val onDerive: (branchName: String) -> Unit = {},
    private val nameValidator: (String) -> Boolean = { true },
    private val gitClient: GitClient,
    private val scope: CoroutineScope,
) : JPanel() {

    private var original: Preset = initial

    private val mainCombo = makeBranchCombo(::updateDirty)
    private val saveBtn = JButton(Bundle.msg("action.save"), AllIcons.Actions.MenuSaveall)
        .apply { isEnabled = false }.noFocusRing()
    private val revertBtn = JButton(Bundle.msg("action.discard"), AllIcons.Actions.Rollback)
        .apply { isEnabled = false }.noFocusRing()
    private val addSubBtn = JButton(Bundle.msg("action.add.submodule"), AllIcons.General.Add).noFocusRing()
    private val arrow = JLabel(AllIcons.General.ArrowRight)
    private val nameLabel = JLabel(initial.name).apply {
        toolTipText = Bundle.msg("label.rename.tip")
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    rename()
                }
            }
        })
    }
    private val currentBadge = JLabel(Bundle.msg("label.current.badge")).apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        isVisible = false
    }
    private val mainDiffLabel = JLabel().apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        isVisible = false
        border = JBUI.Borders.empty(1, 0, 0, 0)
    }
    private val switchBtn = JButton(Bundle.msg("action.switch"), AllIcons.Actions.Execute).noFocusRing()
    private var isCurrent = false

    private val body = object : JPanel() {
        override fun getMaximumSize(): Dimension =
            Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private var loadedOnce = false
    private var initializing = true

    // ── Submodule manager ──────────────────────────────────────
    private val subManager = SubmoduleRowManager(gitRoot, gitClient, scope, body, log, ::updateDirty)
    private val subRows get() = subManager.subRows
    val loadingCount get() = subManager.loadingCount

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = makeBorder(highlighted = false)

        val header = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.source !is JButton) toggle()
                }
            })
        }
        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(arrow)
            add(nameLabel.apply { font = font.deriveFont(Font.BOLD) })
            add(currentBadge)
        }
        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(nameRow)
            add(mainDiffLabel)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggle() }
            })
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        switchBtn.addActionListener { onSwitch(buildCurrent()) }
        right.add(JButton(Bundle.msg("action.derive"), AllIcons.Vcs.Branch).noFocusRing().also {
            it.toolTipText = Bundle.msg("action.derive.tip")
            it.addActionListener { deriveBranch() }
        })
        right.add(switchBtn)
        right.add(JButton(Bundle.msg("action.delete"), AllIcons.Actions.Cancel).noFocusRing().also {
            it.foreground = NamedColorUtil.getErrorForeground()
            it.addActionListener { onDelete() }
        })

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)

        body.add(makeMainRow())
        initial.submodules.forEach { (path, branch) ->
            body.add(subManager.buildSubRow(path, branch).panel)
        }
        val actions = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 8, 4, 4)
        }
        addSubBtn.addActionListener { subManager.showAddSubmoduleMenu(addSubBtn, original) }
        revertBtn.addActionListener { revert() }
        saveBtn.addActionListener {
            val cur = buildCurrent()
            try {
                onSave(cur)
                original = cur
                subManager.removeDeletedRows()
                body.revalidate()
                body.repaint()
            } catch (e: Exception) {
                log("[error] save failed: ${e.message}")
            }
            updateDirty()
        }
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        leftActions.add(addSubBtn)
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        rightActions.add(revertBtn)
        rightActions.add(saveBtn)
        actions.add(leftActions, BorderLayout.WEST)
        actions.add(rightActions, BorderLayout.EAST)
        body.add(actions)

        add(header)
        add(body)

        applyOriginalToUI()
        initializing = false
    }

    private fun makeMainRow(): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty(2, 12, 2, 4)
            alignmentX = LEFT_ALIGNMENT
            val l = JLabel(Bundle.msg("label.main.repo")).apply {
                preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
            }
            add(l, BorderLayout.WEST)
            add(mainCombo, BorderLayout.CENTER)
        }
    }

    private fun applyOriginalToUI() {
        mainCombo.selectedItem = original.main
        subManager.applyPresetToUI(original)
        updateDirty()
    }

    /** Expands/collapses the preset detail panel. Loads branch lists lazily on first expand. */
    private fun toggle() {
        body.isVisible = !body.isVisible
        arrow.icon = if (body.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (body.isVisible && !loadedOnce) {
            loadedOnce = true
            subManager.onFirstExpand()
            loadBranches()
        }
        revalidate()
        repaint()
    }

    /** Lazy-loads branch lists for all combos on first expand. Must be guarded by [loadedOnce]. */
    private fun loadBranches() {
        loadComboBranches(mainCombo, gitRoot.toFile(), original.main)
        subManager.loadAllBranches(original)
    }

    /** Asynchronously loads branch names into [combo] via [scope], preserving [current] as selected item. */
    private fun loadComboBranches(combo: JComboBox<String>, dir: File, current: String) {
        loadComboBranches(combo, dir, current, gitClient, scope, log,
            onLoadStart = { subManager.loadingCount++ },
            onLoadEnd = { subManager.loadingCount--; updateDirty() },
        )
    }

    companion object {
        private fun makeBorder(highlighted: Boolean): Border {
            val divider = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            return if (highlighted) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        divider,
                        BorderFactory.createMatteBorder(0, 3, 0, 0,
                            JBUI.CurrentTheme.Link.Foreground.ENABLED),
                    ),
                    JBUI.Borders.empty(4, 1, 10, 4),
                )
            } else {
                BorderFactory.createCompoundBorder(
                    divider,
                    JBUI.Borders.empty(4, 4, 10, 4),
                )
            }
        }
    }

    /**
     * Updates the preset header diff label and submodule status dots.
     * Dot colors: gray = not initialized, green = branch matched, red = mismatch.
     */
    fun applyCurrentState(currentBranches: Map<String, String?>) {
        setHighlighted(matchesState(currentBranches))
        val currentMain = currentBranches["."] ?: "(detached)"
        if (currentMain != original.main) {
            mainDiffLabel.text = Bundle.msg("preset.main.diff", currentMain, original.main)
            mainDiffLabel.isVisible = true
            mainDiffLabel.foreground = JBColor(0xE07B00, 0xFFA726)
        } else {
            mainDiffLabel.isVisible = false
        }
        // Update submodule status dots
        original.submodules.forEach { (path, targetBranch) ->
            val row = subRows[path] ?: return@forEach
            if (row.deleted) return@forEach
            val cur = currentBranches[path]
            row.statusDot.foreground = when {
                cur == null -> JBColor(0x9E9E9E, 0x757575) // gray: not initialized
                cur == targetBranch -> JBColor(0x4CAF50, 0x66BB6A) // green: matched
                else -> JBColor(0xF44336, 0xEF5350) // red: mismatch
            }
            row.statusDot.toolTipText = when {
                cur == null -> "$path: 未初始化"
                cur == targetBranch -> "$path: $cur ✓"
                else -> "$path: $cur → $targetBranch"
            }
        }
    }

    fun matchesState(currentBranches: Map<String, String?>): Boolean {
        val mainMatches = currentBranches["."] == original.main
        val subsMatch = original.submodules.all { (path, branch) ->
            currentBranches[path] == branch
        }
        return mainMatches && subsMatch
    }

    private fun setHighlighted(highlighted: Boolean) {
        val changed = highlighted != isCurrent
        isCurrent = highlighted
        currentBadge.isVisible = highlighted
        if (highlighted && switchBtn.isFocusOwner) {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
        }
        switchBtn.isEnabled = !highlighted
        switchBtn.text = if (highlighted) Bundle.msg("action.already.on") else Bundle.msg("action.switch")
        switchBtn.toolTipText = if (highlighted) Bundle.msg("action.already.on.tip") else null
        border = makeBorder(highlighted)
        if (changed) {
            repaint()
        }
    }

    private fun buildCurrent(): Preset {
        val newSubs = LinkedHashMap<String, String>()
        subRows.values.forEach { row ->
            if (row.deleted) return@forEach
            newSubs[row.path] = (row.combo.selectedItem as? String)?.trim() ?: ""
        }
        return original.copy(
            main = (mainCombo.selectedItem as? String)?.trim() ?: original.main,
            submodules = newSubs,
        )
    }

    private fun revert() {
        applyOriginalToUI()
    }

    private fun updateDirty() {
        if (initializing || loadingCount > 0) {
            saveBtn.isEnabled = false
            revertBtn.isEnabled = false
            return
        }
        val cur = buildCurrent()
        val dirty = cur != original
        saveBtn.isEnabled = dirty
        revertBtn.isEnabled = dirty
    }

    fun currentPreset(): Preset = original

    fun updatePresetName(newName: String) {
        original = original.copy(name = newName)
        nameLabel.text = newName
    }

    private fun rename() {
        val result = com.intellij.openapi.ui.Messages.showInputDialog(
            Bundle.msg("dialog.rename") + ":",
            Bundle.msg("dialog.rename"),
            null, original.name, null,
        )
        if (result.isNullOrBlank()) return
        val newName = result.trim()
        if (newName == original.name) return
        if (!nameValidator(newName)) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                Bundle.msg("dialog.preset.name.rule"),
                Bundle.msg("dialog.rename"),
            )
            return
        }
        val renamed = original.copy(name = newName)
        try {
            onSave(renamed)
            updatePresetName(newName)
        } catch (e: Exception) {
            log("[error] rename failed: ${e.message}")
        }
    }

    private fun deriveBranch() {
        val preset = buildCurrent()
        val result = com.intellij.openapi.ui.Messages.showInputDialog(
            Bundle.msg("dialog.derive.message", preset.name),
            Bundle.msg("dialog.derive"),
            null, "${preset.name}/feature/",
            null,
        )
        if (result.isNullOrBlank()) return
        onDerive(result.trim())
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
