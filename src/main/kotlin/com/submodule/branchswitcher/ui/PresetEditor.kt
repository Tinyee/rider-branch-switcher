package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.isValidBranchName
import com.submodule.branchswitcher.presentation.PresetDraftSelection
import com.submodule.branchswitcher.presentation.PresetRenameDecision
import com.submodule.branchswitcher.presentation.SubmoduleDraftSelection
import com.submodule.branchswitcher.presentation.applyTo
import com.submodule.branchswitcher.presentation.decidePresetRename
import com.submodule.branchswitcher.presentation.hasUnsavedPresetChanges
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
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

    private val nameLabel = ShrinkableLabel(initialPreset.name).apply {
        toolTipText = presetNameTooltip(initialPreset.name)
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
    private val mainDiffLabel = ShrinkableLabel().apply {
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
    private val headerActions = createHeaderActions()
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
        return ResponsiveRowPanel(
            leading = createPresetIdentity(),
            trailing = headerActions,
            horizontalGap = JBUI.scale(8),
            verticalGap = JBUI.scale(8),
            // Keep wrapped actions on the same content line as the editor controls.
            stackedTrailingIndent = JBUI.scale(12),
            arrangement = ResponsiveRowArrangement.SPACE_BETWEEN,
        ).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggle() }
            })
        }
    }

    private fun createPresetIdentity(): JPanel {
        val coreIdentity = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(arrow.apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { toggle() }
                })
            })
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(4)))
            add(nameLabel.apply { font = font.deriveFont(Font.BOLD) })
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(4)))
            add(currentBadge)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggle() }
            })
        }
        return ResponsiveRowPanel(
            leading = coreIdentity,
            trailing = mainDiffLabel,
            horizontalGap = JBUI.scale(4),
            verticalGap = JBUI.scale(2),
            // The subtitle starts below the name, after the arrow and its gap.
            stackedTrailingIndent = JBUI.scale(20),
            arrangement = ResponsiveRowArrangement.PACKED,
        )
    }

    private fun createHeaderActions(): CollapsibleActionBar =
        CollapsibleActionBar(
            primary = switchBtn,
            secondary = deriveBtn,
            overflow = moreButton,
            responsiveContext = this,
        ).apply {
            switchBtn.addActionListener { onSwitch(buildEditedPreset()) }
        }

    private fun createEditorActions(): JPanel {
        addSubBtn.addActionListener {
            submoduleManager.showAddSubmoduleMenu(addSubBtn, savedPreset)
        }
        revertBtn.addActionListener { revert() }
        saveBtn.addActionListener { saveChanges() }
        val rightActions = ResponsiveRowPanel(
            leading = revertBtn,
            trailing = saveBtn,
            horizontalGap = JBUI.scale(4),
            verticalGap = JBUI.scale(4),
            // The outer row moves this group to the right on wide layouts.
            arrangement = ResponsiveRowArrangement.PACKED,
        )
        return ResponsiveRowPanel(
            leading = addSubBtn,
            trailing = rightActions,
            horizontalGap = JBUI.scale(8),
            verticalGap = JBUI.scale(4),
            arrangement = ResponsiveRowArrangement.SPACE_BETWEEN,
        ).apply {
            border = JBUI.Borders.empty(8, 8, 4, 4)
        }
    }

    @Suppress("TooGenericExceptionCaught") // persistence callbacks may expose unrelated IO and serialization failures
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
            log.failure("save failed", e)
            updateUnsavedState()
        }
    }

    private fun createMainRepositoryRow(): JPanel {
        val mainRepositoryLabel = JLabel(Bundle.msg("label.main.repo"))
        return responsiveFormRow(mainRepositoryLabel, mainCombo)
    }

    /** Creates the "..." more-actions button for this preset card. */
    private fun createPresetMoreButton(): JButton {
        return jButton(icon = AllIcons.Actions.MoreHorizontal) {
            margin = JBUI.insets(0, 4, 0, 4)
            preferredSize = Dimension(JBUI.scale(32), preferredSize.height)
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = Bundle.msg("action.more.tip")
            addActionListener { showPresetMoreMenu(this) }
        }
    }

    private fun showPresetMoreMenu(anchor: JButton) {
        val overflowActions = if (deriveBtn.isVisible) {
            emptyList()
        } else {
            listOf(
                PopupAction(
                    Bundle.msg("action.derive.branch"),
                    AllIcons.Vcs.Branch,
                    perform = ::deriveBranch,
                ),
            )
        }
        showActionPopup(
            anchor,
            listOf(
                overflowActions,
                listOf(
                    PopupAction(
                        Bundle.msg("action.delete.preset"),
                        AllIcons.General.Delete,
                        danger = true,
                        perform = onDelete,
                    ),
                ),
            ),
        )
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
        } else if (!body.isVisible) {
            val cancelledMain = cancelComboBranchLoad(mainCombo)
            val cancelledSubmodules = submoduleManager.cancelBranchLoads()
            // Also reset when a submodule row still lacks its branch list (a completed
            // but failed load), so re-expanding retries it even though the main loaded.
            if (cancelledMain || cancelledSubmodules || submoduleManager.hasUnloadedRows()) branchesLoaded = false
        }
        body.revalidate()
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
        loadComboBranches(combo, dir, current, branchLoads, log,
            onLoadStart = { submoduleManager.loadingCount++ },
            onLoadEnd = { succeeded, superseded ->
                submoduleManager.loadingCount--
                if (!succeeded && !superseded) branchesLoaded = false
                updateUnsavedState()
            },
        )
    }

    /** Stops background work before this editor is removed from the Tool Window. */
    fun dispose() {
        cancelComboBranchLoad(mainCombo)
        submoduleManager.cancelBranchLoads()
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
        mainDiffLabel.toolTipText = mainStatus
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
        switchBtn.isVisible = !highlighted
        headerActions.refreshLayoutState()
        border = makeBorder(highlighted)
        if (changed) {
            revalidate()
            repaint()
        }
    }

    private fun draftSelection(): PresetDraftSelection =
        PresetDraftSelection(
            mainBranch = mainCombo.selectedItem as? String,
            submodules = submoduleRows.values.map { row ->
                SubmoduleDraftSelection(
                    path = row.path,
                    branch = row.combo.selectedItem as? String,
                    included = !row.deleted,
                )
            },
        )

    private fun buildEditedPreset(): Preset = draftSelection().applyTo(savedPreset)

    private fun revert() {
        restoreSavedPresetToUi()
    }

    private fun updateUnsavedState() {
        val hasUnsavedChanges = hasUnsavedPresetChanges(
            savedPreset = savedPreset,
            draftSelection = draftSelection(),
            editingBlocked = isInitializing || loadingCount > 0 || persistenceInProgress,
        )
        saveBtn.isEnabled = hasUnsavedChanges
        revertBtn.isEnabled = hasUnsavedChanges
    }

    fun currentPreset(): Preset = savedPreset

    private fun updatePresetName(newName: String) {
        savedPreset = savedPreset.copy(name = newName)
        nameLabel.text = newName
        nameLabel.toolTipText = presetNameTooltip(newName)
    }

    private fun presetNameTooltip(name: String): String =
        "$name (${Bundle.msg("label.rename.tip")})"

    @Suppress("TooGenericExceptionCaught") // rename persists through the same callback boundary as save
    private fun rename() {
        if (persistenceInProgress) return
        val requestedName = com.intellij.openapi.ui.Messages.showInputDialog(
            Bundle.msg("dialog.rename") + ":",
            Bundle.msg("dialog.rename"),
            null, savedPreset.name, null,
        )
        val renamed = when (val decision = decidePresetRename(savedPreset, requestedName, nameValidator)) {
            PresetRenameDecision.Ignore -> return
            PresetRenameDecision.Invalid -> {
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    Bundle.msg("dialog.preset.name.rule"),
                    Bundle.msg("dialog.rename"),
                )
                return
            }
            is PresetRenameDecision.Rename -> decision.preset
        }
        persistenceInProgress = true
        updateUnsavedState()
        try {
            onSave(renamed) { saved ->
                if (saved) updatePresetName(renamed.name)
                persistenceInProgress = false
                updateUnsavedState()
            }
        } catch (e: Exception) {
            persistenceInProgress = false
            log.failure("rename failed", e)
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
