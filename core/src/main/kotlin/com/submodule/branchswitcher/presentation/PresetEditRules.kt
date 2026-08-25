package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.Preset

/** Raw branch selections read from one preset editor. */
data class PresetDraftSelection(
    val mainBranch: String?,
    val submodules: List<SubmoduleDraftSelection>,
)

data class SubmoduleDraftSelection(
    val path: String,
    val branch: String?,
    val included: Boolean,
)

/** Builds the pending preset without exposing Swing components to edit rules. */
fun PresetDraftSelection.applyTo(savedPreset: Preset): Preset {
    val selectedSubmodules = LinkedHashMap<String, String>()
    submodules.forEach { selection ->
        if (selection.included) {
            selectedSubmodules[selection.path] = selection.branch?.trim().orEmpty()
        }
    }
    return savedPreset.copy(
        main = mainBranch?.trim() ?: savedPreset.main,
        submodules = selectedSubmodules,
    )
}

/**
 * True when the draft differs from the saved preset. Content-only — no busy-gate logic lives
 * here. The init/load/persistence/mutation gates are owned solely by [presetEditorControlState],
 * so a call that passes "is editing blocked" here would split the busy rule across two owners.
 */
fun hasPresetDraftChanges(
    savedPreset: Preset,
    draftSelection: PresetDraftSelection,
): Boolean = draftSelection.applyTo(savedPreset) != savedPreset

sealed interface PresetRenameDecision {
    data object Ignore : PresetRenameDecision
    data object Invalid : PresetRenameDecision
    data class Rename(val preset: Preset) : PresetRenameDecision
}

/** Classifies rename input before the UI performs persistence or shows validation feedback. */
fun decidePresetRename(
    savedPreset: Preset,
    requestedName: String?,
    nameAvailable: (String) -> Boolean,
): PresetRenameDecision {
    val newName = requestedName?.trim().orEmpty()
    if (newName.isEmpty() || newName == savedPreset.name) return PresetRenameDecision.Ignore
    if (!nameAvailable(newName)) return PresetRenameDecision.Invalid
    return PresetRenameDecision.Rename(savedPreset.copy(name = newName))
}

/** Every enabled/disabled state a preset editor's buttons take, derived from one snapshot. */
data class PresetEditorControlState(
    val saveEnabled: Boolean,
    val revertEnabled: Boolean,
    val switchEnabled: Boolean,
    val deriveEnabled: Boolean,
)

/**
 * Derives all four button states from one snapshot of the editor's inputs, so the save/revert
 * pair and the switch/derive pair can never escape from under the same busy rule. They answer
 * different gates: save/revert depend on there being an unsaved draft, switch/derive depend on
 * the global mutation gate — but both are disabled by editor-local busy (init, branch load, or
 * persistence), and releasing one source of busy must not re-enable an action another still
 * blocks (e.g. a mutation ending must not re-enable a still-loading editor).
 */
fun presetEditorControlState(
    hasDraftChanges: Boolean,
    initializing: Boolean,
    loadingCount: Int,
    persistenceInProgress: Boolean,
    mutationActionsEnabled: Boolean,
): PresetEditorControlState {
    val localBusy = initializing || loadingCount > 0 || persistenceInProgress
    val saveRevertEnabled = hasDraftChanges && !localBusy
    val switchDeriveEnabled = mutationActionsEnabled && !localBusy
    return PresetEditorControlState(
        saveEnabled = saveRevertEnabled,
        revertEnabled = saveRevertEnabled,
        switchEnabled = switchDeriveEnabled,
        deriveEnabled = switchDeriveEnabled,
    )
}
