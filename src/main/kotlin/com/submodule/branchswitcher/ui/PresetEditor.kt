package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.isValidBranchName
import com.submodule.branchswitcher.presentation.shouldShowSecondaryAction
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.border.Border

/**
 * Expandable panel for a single preset. Shows the preset name, main repo combo,
 * and one [SubRow] per submodule. Branch lists are loaded lazily on first expand
 * via [scope] coroutines.
 *
 * State tracking:
 * - [branchesLoaded]: whether the branch combo lists have been loaded (lazy, on first expand)
 * - [loadingCount]: number of in-flight async branch loads; [updateUnsavedState] suppresses
 *   the dirty check while any load is pending
 * - [isInitializing]: true during constructor; prevents false dirty flags during setup
 */
internal class PresetEditor(
    private val gitRoot: Path,
    initialPreset: Preset,
    private val log: AppLogger,
    private val onSwitch: (Preset) -> Unit,
    private val onSave: (Preset, (Boolean) -> Unit) -> Unit,
    private val onDelete: () -> Unit,
    private val onDerive: (preset: Preset, branchName: String) -> Unit = { _, _ -> },
    private val nameValidator: (String) -> Boolean = { true },
    private val gitClient: () -> PresetDiscoveryGitClient,
    private val branchLoads: BranchLoadCoordinator,
    private val onSwitchOnly: (path: String, target: String) -> Unit = { _, _ -> },
) : JPanel() {

    private var savedPreset: Preset = initialPreset

    private val mainCombo = makeBranchCombo(::updateUnsavedState)
    private val saveBtn = jButton(Bundle.msg("action.save"), AllIcons.Actions.MenuSaveall) { isEnabled = false }
    private val revertBtn = jButton(Bundle.msg("action.discard"), AllIcons.Actions.Rollback) { isEnabled = false }
    private val addSubBtn = jButton(Bundle.msg("action.add.submodule"), AllIcons.General.Add)
    private val arrow = JLabel(AllIcons.General.ArrowRight)

    private val nameLabel = JLabel(initialPreset.name).apply {
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
    private val switchBtn = jButton(Bundle.msg("action.switch"), AllIcons.Actions.Execute)
    private val deriveBtn = jButton(Bundle.msg("action.derive"), AllIcons.Vcs.Branch) {
        toolTipText = Bundle.msg("action.derive.tip")
        addActionListener { deriveBranch() }
    }
    private val moreButton = createPresetMoreButton()
    private var isCurrentPreset = false

    private val body = CompactHeightPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private var branchesLoaded = false
    private var isInitializing = true
    private var persistenceInProgress = false

    private val submoduleManager = SubmoduleRowManager(
        gitRoot, gitClient, branchLoads, body, log, ::updateUnsavedState, onSwitchOnly,
    )
    private val submoduleRows get() = submoduleManager.subRows
    val loadingCount get() = submoduleManager.loadingCount

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = makeBorder(highlighted = false)

        val header = createHeader()
        body.add(createMainRepositoryRow())
        initialPreset.submodules.forEach { (path, branch) ->
            body.add(submoduleManager.buildSubRow(path, branch).panel)
        }
        body.add(createEditorActions())
        add(header)
        add(body)

        restoreSavedPresetToUi()
        isInitializing = false
    }

    private fun createHeader(): JPanel {
        val header = createHeaderContainer()
        val nameRow = createPresetNameRow()
        val headerActions = createHeaderActions()

        nameRow.add(Box.createHorizontalStrut(4))
        nameRow.add(mainDiffLabel)
        header.add(nameRow)
        header.add(headerActions)
        installResponsiveHeaderActions(header, headerActions)
        return header
    }

    private fun createHeaderContainer(): JPanel =
        CompactHeightPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.source !is JButton) toggle()
                }
            })
        }

    private fun createPresetNameRow(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(arrow.apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { toggle() }
                })
            })
            add(nameLabel.apply { font = font.deriveFont(Font.BOLD) })
            add(currentBadge)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggle() }
            })
        }

    private fun createHeaderActions(): JPanel =
        CompactHeightPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            switchBtn.addActionListener { onSwitch(buildEditedPreset()) }
            add(switchBtn)
            add(deriveBtn)
            add(moreButton)
        }

    private fun installResponsiveHeaderActions(header: JPanel, headerActions: JPanel) {
        header.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                refreshResponsiveHeaderActions(header, headerActions)
            }
        })
        val buttonMetricsChanged = java.beans.PropertyChangeListener {
            refreshResponsiveHeaderActions(header, headerActions)
        }
        listOf(switchBtn, deriveBtn, moreButton).forEach { button ->
            button.addPropertyChangeListener("text", buttonMetricsChanged)
            button.addPropertyChangeListener("font", buttonMetricsChanged)
            button.addPropertyChangeListener("icon", buttonMetricsChanged)
        }
    }

    private fun refreshResponsiveHeaderActions(header: JPanel, headerActions: JPanel) {
        val requiredWidth = requiredHeaderActionsWidth(headerActions)
        val showDerive = shouldShowSecondaryAction(header.width, requiredWidth)
        if (deriveBtn.isVisible != showDerive) {
            deriveBtn.isVisible = showDerive
            headerActions.revalidate()
            headerActions.repaint()
        }
    }

    private fun requiredHeaderActionsWidth(headerActions: JPanel): Int {
        val flowLayout = headerActions.layout as FlowLayout
        val buttonsWidth = switchBtn.preferredSize.width +
            deriveBtn.preferredSize.width +
            moreButton.preferredSize.width
        return buttonsWidth +
            flowLayout.hgap * 4 +
            headerActions.insets.left +
            headerActions.insets.right +
            JBUI.scale(16)
    }

    private fun createEditorActions(): JPanel {
        val editorActions = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 8, 4, 4)
        }
        addSubBtn.addActionListener {
            submoduleManager.showAddSubmoduleMenu(addSubBtn, savedPreset)
        }
        revertBtn.addActionListener { revert() }
        saveBtn.addActionListener { saveChanges() }
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        leftActions.add(addSubBtn)
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        rightActions.add(revertBtn)
        rightActions.add(saveBtn)
        editorActions.add(leftActions, BorderLayout.WEST)
        editorActions.add(rightActions, BorderLayout.EAST)
        return editorActions
    }

    private fun saveChanges() {
        if (persistenceInProgress) return
        val editedPreset = buildEditedPreset()
        persistenceInProgress = true
        updateUnsavedState()
        try {
            onSave(editedPreset) { saved ->
                if (saved) {
                    savedPreset = editedPreset
                    submoduleManager.removeDeletedRows()
                    body.revalidate()
                    body.repaint()
                }
                persistenceInProgress = false
                updateUnsavedState()
            }
        } catch (e: Exception) {
            persistenceInProgress = false
            log.error("save failed: ${e.message}")
            updateUnsavedState()
        }
    }

    private fun createMainRepositoryRow(): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty(2, 12, 2, 4)
            alignmentX = LEFT_ALIGNMENT
            val mainRepositoryLabel = JLabel(Bundle.msg("label.main.repo")).apply {
                preferredSize = Dimension(JBUI.scale(140), preferredSize.height)
            }
            add(mainRepositoryLabel, BorderLayout.WEST)
            add(mainCombo, BorderLayout.CENTER)
        }
    }

    /** Creates the "..." more-actions button for this preset card. */
    private fun createPresetMoreButton(): JButton {
        return jButton(icon = AllIcons.Actions.MoreHorizontal) {
            margin = JBUI.insets(0, 4, 0, 4)
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = Bundle.msg("action.more.tip")
            addActionListener { showPresetMoreMenu(this) }
        }
    }

    private fun showPresetMoreMenu(anchor: JButton) {
        val menu = JPopupMenu()
        if (!deriveBtn.isVisible) {
            val deriveItem = JMenuItem(Bundle.msg("action.derive"), AllIcons.Vcs.Branch)
            deriveItem.addActionListener { deriveBranch() }
            menu.add(deriveItem)
            menu.addSeparator()
        }

        val deleteItem = JMenuItem(Bundle.msg("action.delete"), AllIcons.Actions.Cancel)
        deleteItem.foreground = NamedColorUtil.getErrorForeground()
        deleteItem.addActionListener { onDelete() }
        menu.add(deleteItem)
        menu.show(anchor, 0, anchor.height)
    }

    private fun restoreSavedPresetToUi() {
        mainCombo.selectedItem = savedPreset.main
        submoduleManager.applyPresetToUI(savedPreset)
        updateUnsavedState()
    }

    /** Expands/collapses the preset detail panel. Loads branch lists lazily on first expand. */
    private fun toggle() {
        body.isVisible = !body.isVisible
        arrow.icon = if (body.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (body.isVisible && !branchesLoaded) {
            branchesLoaded = true
            submoduleManager.onFirstExpand()
            loadBranches()
        }
        revalidate()
        repaint()
    }

    /** Lazy-loads branch lists for all combos on first expand. Must be guarded by [branchesLoaded]. */
    private fun loadBranches() {
        loadComboBranches(mainCombo, gitRoot.toFile(), savedPreset.main)
        submoduleManager.loadAllBranches(savedPreset)
    }

    /** Asynchronously loads branch names into [combo] via [scope], preserving [current] as selected item. */
    private fun loadComboBranches(combo: JComboBox<String>, dir: File, current: String) {
        loadComboBranches(combo, dir, current, gitClient, branchLoads, log,
            onLoadStart = { submoduleManager.loadingCount++ },
            onLoadEnd = {
                submoduleManager.loadingCount--
                updateUnsavedState()
            },
        )
    }

    /**
     * Updates the preset header diff label and submodule status dots.
     * Dot colors: gray = not initialized, green = branch matched, orange = different branch.
     * When [dirtyRepos] marks a path as dirty, the tooltip appends a warning.
     */
    fun applyCurrentState(currentBranches: Map<String, String?>, dirtyRepos: Map<String, Boolean> = emptyMap()) {
        setHighlighted(matchesState(currentBranches))
        val currentMain = currentBranches["."] ?: Bundle.msg("status.detached")
        val mainDirty = dirtyRepos["."] == true
        val mainStatus = mainStatusText(currentMain, savedPreset.main, mainDirty)
        mainDiffLabel.text = mainStatus
        mainDiffLabel.isVisible = mainStatus != null
        mainDiffLabel.foreground = JBColor(0xE07B00, 0xFFA726)
        // Update submodule status dots
        savedPreset.submodules.forEach { (path, targetBranch) ->
            val row = submoduleRows[path] ?: return@forEach
            if (row.deleted) return@forEach
            val currentBranch = currentBranches[path]
            val isDirty = dirtyRepos[path] == true
            val presentation = repoStatusPresentation(path, currentBranch, targetBranch, isDirty)
            row.statusDot.foreground = when (presentation.tone) {
                RepoStatusTone.NOT_INITIALIZED -> JBColor(0x9E9E9E, 0x757575)
                RepoStatusTone.MATCHED -> JBColor(0x4CAF50, 0x66BB6A)
                RepoStatusTone.MISMATCHED -> JBColor(0xE07B00, 0xFFA726)
            }
            row.statusDot.toolTipText = presentation.tooltip
        }
    }

    fun matchesState(currentBranches: Map<String, String?>): Boolean {
        val mainMatches = currentBranches["."] == savedPreset.main
        val subsMatch = savedPreset.submodules.all { (path, branch) ->
            currentBranches[path] == branch
        }
        return mainMatches && subsMatch
    }

    private fun setHighlighted(highlighted: Boolean) {
        val changed = highlighted != isCurrentPreset
        isCurrentPreset = highlighted
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

    private fun buildEditedPreset(): Preset {
        val selectedSubmoduleBranches = LinkedHashMap<String, String>()
        submoduleRows.values.forEach { row ->
            if (row.deleted) return@forEach
            selectedSubmoduleBranches[row.path] =
                (row.combo.selectedItem as? String)?.trim() ?: ""
        }
        return savedPreset.copy(
            main = (mainCombo.selectedItem as? String)?.trim() ?: savedPreset.main,
            submodules = selectedSubmoduleBranches,
        )
    }

    private fun revert() {
        restoreSavedPresetToUi()
    }

    private fun updateUnsavedState() {
        if (isInitializing || loadingCount > 0 || persistenceInProgress) {
            saveBtn.isEnabled = false
            revertBtn.isEnabled = false
            return
        }
        val editedPreset = buildEditedPreset()
        val hasUnsavedChanges = editedPreset != savedPreset
        saveBtn.isEnabled = hasUnsavedChanges
        revertBtn.isEnabled = hasUnsavedChanges
    }

    fun currentPreset(): Preset = savedPreset

    private fun updatePresetName(newName: String) {
        savedPreset = savedPreset.copy(name = newName)
        nameLabel.text = newName
    }

    private fun rename() {
        if (persistenceInProgress) return
        val requestedName = com.intellij.openapi.ui.Messages.showInputDialog(
            Bundle.msg("dialog.rename") + ":",
            Bundle.msg("dialog.rename"),
            null, savedPreset.name, null,
        )
        if (requestedName.isNullOrBlank()) return
        val newName = requestedName.trim()
        if (newName == savedPreset.name) return
        if (!nameValidator(newName)) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                Bundle.msg("dialog.preset.name.rule"),
                Bundle.msg("dialog.rename"),
            )
            return
        }
        val renamed = savedPreset.copy(name = newName)
        persistenceInProgress = true
        updateUnsavedState()
        try {
            onSave(renamed) { saved ->
                if (saved) updatePresetName(newName)
                persistenceInProgress = false
                updateUnsavedState()
            }
        } catch (e: Exception) {
            persistenceInProgress = false
            log.error("rename failed: ${e.message}")
            updateUnsavedState()
        }
    }

    private fun deriveBranch() {
        val preset = buildEditedPreset()
        val requestedBranchName = com.intellij.openapi.ui.Messages.showInputDialog(
            Bundle.msg("dialog.derive.message", preset.name),
            Bundle.msg("dialog.derive"),
            null, "${preset.name}/feature/",
            null,
        )
        if (requestedBranchName.isNullOrBlank()) return
        val branchName = requestedBranchName.trim()
        if (!isValidBranchName(branchName)) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                Bundle.msg("dialog.derive.invalid.name", branchName),
                Bundle.msg("dialog.derive"))
            return
        }
        onDerive(preset, branchName)
    }


    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    companion object {
        private fun makeBorder(highlighted: Boolean): Border {
            val divider = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            return if (highlighted) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        divider,
                        BorderFactory.createMatteBorder(
                            0,
                            3,
                            0,
                            0,
                            JBUI.CurrentTheme.Link.Foreground.ENABLED,
                        ),
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
}
