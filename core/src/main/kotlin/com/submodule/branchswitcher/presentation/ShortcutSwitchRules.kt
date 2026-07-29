package com.submodule.branchswitcher.presentation

/** Decides whether the shortcut may continue from preset loading to selection. */
fun shortcutPresetLoadDecision(loadSucceeded: Boolean, presetCount: Int): ShortcutPresetLoadDecision =
    when {
        !loadSucceeded -> ShortcutPresetLoadDecision.LoadFailed
        presetCount == 0 -> ShortcutPresetLoadDecision.NoPresets
        else -> ShortcutPresetLoadDecision.Ready
    }

enum class ShortcutPresetLoadDecision { LoadFailed, NoPresets, Ready }
