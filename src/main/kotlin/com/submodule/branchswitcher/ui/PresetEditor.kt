package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
import javax.swing.SwingUtilities
import javax.swing.border.Border

class PresetEditor(
    private val gitRoot: Path,
    initial: Preset,
    private val log: (String) -> Unit,
    private val onSwitch: (Preset) -> Unit,
    private val onSave: (Preset) -> Unit,
    private val onDelete: () -> Unit,
    private val onDerive: (branchName: String) -> Unit = {},
    private val onMoveUp: () -> Unit = {},
    private val onMoveDown: () -> Unit = {},
    private val gitClient: GitClient,
    private val scope: CoroutineScope,
) : JPanel() {

    private class SubRow(
        val path: String,
        val combo: JComboBox<String>,
        var panel: JPanel,
        var deleted: Boolean = false,
        var loaded: Boolean = false,
        val statusDot: JLabel = JLabel("●").apply {
            font = font.deriveFont(8f)
            foreground = JBColor(0x9E9E9E, 0x757575)
        },
    )

    private var original: Preset = initial

    private val mainCombo = makeBranchCombo(::updateDirty)
    private val subRows = LinkedHashMap<String, SubRow>()
    private val saveBtn = JButton(Strings.save, AllIcons.Actions.MenuSaveall)
        .apply { isEnabled = false }.noFocusRing()
    private val revertBtn = JButton(Strings.discard, AllIcons.Actions.Rollback)
        .apply { isEnabled = false }.noFocusRing()
    private val addSubBtn = JButton(Strings.addSubmodule, AllIcons.General.Add).noFocusRing()
    private val arrow = JLabel(AllIcons.General.ArrowRight)
    private val nameLabel = JLabel(initial.name).apply {
        toolTipText = Strings.renameTip
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    rename()
                }
            }
        })
    }
    private val currentBadge = JLabel("· 当前").apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        isVisible = false
    }
    private val mainDiffLabel = JLabel().apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        isVisible = false
        border = JBUI.Borders.empty(1, 0, 0, 0)
    }
    private val switchBtn = JButton("切换", AllIcons.Actions.Execute).noFocusRing().apply {
        preferredSize = Dimension(JBUI.scale(56), preferredSize.height)
    }
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
    private var loadingCount = 0
    private var initializing = true

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
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
        // Small icon-only header buttons
        fun smallBtn(icon: javax.swing.Icon, tip: String, action: () -> Unit): JButton =
            JButton(icon).noFocusRing().apply {
                toolTipText = tip
                addActionListener { action() }
                preferredSize = Dimension(24, 24)
            }
        right.add(smallBtn(AllIcons.Actions.MoveUp, "上移此预设") { onMoveUp() })
        right.add(smallBtn(AllIcons.Actions.MoveDown, "下移此预设") { onMoveDown() })
        switchBtn.addActionListener { onSwitch(buildCurrent()) }
        right.add(smallBtn(AllIcons.Vcs.Branch, Strings.deriveTip) { deriveBranch() })
        right.add(switchBtn)
        right.add(smallBtn(AllIcons.Actions.Cancel, "删除") { onDelete() }.also {
            it.foreground = NamedColorUtil.getErrorForeground()
        })

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)

        body.add(makeMainRow())
        initial.submodules.keys.forEach { path ->
            body.add(buildSubRow(path).panel)
        }
        val actions = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 8, 4, 4)
        }
        addSubBtn.addActionListener { showAddSubmoduleMenu() }
        revertBtn.addActionListener { revert() }
        saveBtn.addActionListener {
            val cur = buildCurrent()
            original = cur
            onSave(cur)
            subRows.entries.removeAll { (_, row) ->
                if (row.deleted) { body.remove(row.panel); true } else false
            }
            body.revalidate()
            body.repaint()
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
            val l = JLabel(Strings.mainRepo).apply {
                preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
            }
            add(l, BorderLayout.WEST)
            add(mainCombo, BorderLayout.CENTER)
        }
    }

    private fun buildSubRow(path: String): SubRow {
        val combo = makeBranchCombo(::updateDirty)
        val dot = JLabel("●").apply {
            font = font.deriveFont(8f)
            foreground = JBColor(0x9E9E9E, 0x757575)
        }
        val row = SubRow(path, combo, JPanel(), statusDot = dot)
        val rowPanel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty(2, 12, 2, 4)
            alignmentX = LEFT_ALIGNMENT
            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(dot)
                add(Box.createHorizontalStrut(4))
                add(JLabel(shortLabel(path)).apply {
                    preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
                    toolTipText = path
                })
            }
            add(labelPanel, BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)

            val delBtn = JButton(AllIcons.General.Remove).apply {
                margin = JBUI.insets(0, 4, 0, 4)
                preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                maximumSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                minimumSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                toolTipText = "从此预设移除该子模块（保存后切换将不动它）"
                addActionListener {
                    val r = subRows[path] ?: return@addActionListener
                    r.deleted = true
                    r.panel.isVisible = false
                    updateDirty()
                    body.revalidate()
                    body.repaint()
                }
            }.noFocusRing()
            add(delBtn, BorderLayout.EAST)
        }
        // Right-click context menu
        rowPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e, path) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e, path) }
            override fun mouseClicked(e: MouseEvent) {}
        })
        row.panel = rowPanel
        subRows[path] = row
        return row
    }

    private fun shortLabel(path: String): String {
        if (!path.contains("/")) return path
        return path.substringAfterLast('/').removeSuffix("~")
    }

    private fun applyOriginalToUI() {
        mainCombo.selectedItem = original.main
        val orphan = mutableListOf<String>()
        subRows.values.forEach { row ->
            if (original.submodules.containsKey(row.path)) {
                row.deleted = false
                row.panel.isVisible = true
                row.combo.selectedItem = original.submodules[row.path]
            } else {
                orphan += row.path
            }
        }
        orphan.forEach { path ->
            val row = subRows.remove(path) ?: return@forEach
            body.remove(row.panel)
        }
        body.revalidate()
        body.repaint()
        updateDirty()
    }

    private fun showAddSubmoduleMenu() {
        val all = gitClient.listSubmodulePaths(gitRoot.toFile())
        val current = subRows.values.filter { !it.deleted }.map { it.path }.toSet()
        val available = all.filter { it !in current }
        if (available.isEmpty()) {
            log("所有 .gitmodules 中的子模块均已在「${original.name}」中配置")
            return
        }
        val popup = javax.swing.JPopupMenu()
        available.forEach { path ->
            popup.add(javax.swing.JMenuItem(shortLabel(path)).apply {
                toolTipText = path
                addActionListener { addSubmoduleFromMenu(path) }
            })
        }
        popup.show(addSubBtn, 0, addSubBtn.height)
    }

    private fun addSubmoduleFromMenu(path: String) {
        val existing = subRows[path]
        if (existing != null && existing.deleted) {
            existing.deleted = false
            existing.panel.isVisible = true
            if (loadedOnce && !existing.loaded) {
                existing.loaded = true
                loadComboBranches(existing.combo, gitRoot.resolve(path).toFile(),
                    existing.combo.selectedItem as? String ?: "")
            }
            updateDirty()
            body.revalidate()
            body.repaint()
            return
        }
        val row = buildSubRow(path)
        val actionsIndex = body.componentCount - 1
        body.add(row.panel, actionsIndex)
        val dir = gitRoot.resolve(path).toFile()
        if (!dir.exists()) {
            row.combo.selectedItem = ""
            if (loadedOnce) { row.loaded = true; loadComboBranches(row.combo, dir, "") }
            updateDirty()
        } else {
            row.combo.selectedItem = "loading..."
            row.combo.isEnabled = false
            loadingCount++
            scope.launch {
                val seedBranch = gitClient.currentBranch(dir) ?: ""
                SwingUtilities.invokeLater {
                    row.combo.selectedItem = seedBranch
                    row.combo.isEnabled = true
                    loadingCount--
                    if (loadedOnce) {
                        row.loaded = true
                        loadComboBranches(row.combo, dir, seedBranch)
                    }
                    updateDirty()
                }
            }
        }
        body.revalidate()
        body.repaint()
    }

    private fun toggle() {
        body.isVisible = !body.isVisible
        arrow.icon = if (body.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (body.isVisible && !loadedOnce) {
            loadedOnce = true
            loadBranches()
        }
        revalidate()
        repaint()
    }

    private fun loadBranches() {
        loadComboBranches(mainCombo, gitRoot.toFile(), original.main)
        subRows.values.forEach { row ->
            if (row.deleted || row.loaded) return@forEach
            row.loaded = true
            val dir = gitRoot.resolve(row.path).toFile()
            val branch = original.submodules[row.path] ?: ""
            loadComboBranches(row.combo, dir, branch)
        }
    }

    private fun loadComboBranches(combo: JComboBox<String>, dir: File, current: String) {
        combo.model = DefaultComboBoxModel(arrayOf("loading..."))
        combo.selectedItem = "loading..."
        combo.isEnabled = false
        loadingCount++
        scope.launch {
            val branches = if (dir.exists()) gitClient.listAllBranches(dir) else emptyList()
            SwingUtilities.invokeLater {
                val list = if (current.isNotEmpty() && !branches.contains(current))
                    listOf(current) + branches else branches
                combo.model = DefaultComboBoxModel(list.toTypedArray())
                combo.selectedItem = current
                combo.putClientProperty(KEY_ALL_BRANCHES, list)
                combo.isEnabled = true
                loadingCount--
                updateDirty()
            }
        }
    }

    companion object {
        private const val KEY_ALL_BRANCHES = "submodule.branchswitcher.allBranches"

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

    fun applyCurrentState(currentBranches: Map<String, String?>) {
        setHighlighted(matchesState(currentBranches))
        val currentMain = currentBranches["."] ?: "(detached)"
        if (currentMain != original.main) {
            mainDiffLabel.text = "主仓: $currentMain → ${original.main}"
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
        switchBtn.text = if (highlighted) "✓" else "切换"
        switchBtn.toolTipText = if (highlighted) Strings.alreadyOnPresetTip else "切到此预设"
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

    private fun showContextMenu(e: MouseEvent, path: String) {
        val popup = javax.swing.JPopupMenu()
        popup.add("${Strings.switchOnlyThis} ($path)").addActionListener {
            val dir = gitRoot.resolve(path).toFile()
            if (dir.exists() && java.io.File(dir, ".git").exists()) {
                scope.launch {
                    val cur = gitClient.currentBranch(dir)
                    val target = original.submodules[path] ?: (cur ?: return@launch)
                    val result = gitClient.checkoutExisting(dir, target)
                    SwingUtilities.invokeLater { log(if (result.ok) "[switch] $path -> $target ok" else "[switch] $path fail") }
                }
            }
        }
        popup.add(Strings.openInExplorer).addActionListener {
            val dir = gitRoot.resolve(path).toFile()
            if (dir.exists()) java.awt.Desktop.getDesktop().open(dir)
        }
        popup.show(e.component, e.x, e.y)
    }

    fun updatePresetName(newName: String) {
        original = original.copy(name = newName)
        nameLabel.text = newName
    }

    private fun rename() {
        val result = com.intellij.openapi.ui.Messages.showInputDialog(
            Strings.rename + ":",
            Strings.rename,
            null, original.name, null,
        )
        if (result.isNullOrBlank()) return
        val newName = result.trim()
        if (newName != original.name) {
            updatePresetName(newName)
            onSave(original)
        }
    }

    private fun deriveBranch() {
        val preset = buildCurrent()
        val result = com.intellij.openapi.ui.Messages.showInputDialog(
            "基于「${preset.name}」创建新分支，主仓+所有子模块将同时 checkout -b\n\n输入新分支名:",
            Strings.deriveDialog,
            null, "${preset.name}/feature/",
            null,
        )
        if (result.isNullOrBlank()) return
        onDerive(result.trim())
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
