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

fun hasUnsavedPresetChanges(
    savedPreset: Preset,
    draftSelection: PresetDraftSelection,
    editingBlocked: Boolean,
): Boolean = !editingBlocked && draftSelection.applyTo(savedPreset) != savedPreset

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
