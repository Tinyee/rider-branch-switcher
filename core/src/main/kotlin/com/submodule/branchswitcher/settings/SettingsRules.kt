package com.submodule.branchswitcher.settings

import com.submodule.branchswitcher.model.DirtyAction

// Dropdown order in the settings UI. Using a list means the index IS the position,
// so adding an entry appends safely instead of silently shifting existing options.
private val DIRTY_ACTIONS = listOf(DirtyAction.Stash, DirtyAction.Skip, DirtyAction.Force)
private val TIMEOUTS = listOf(30, 60, 120, 300)

/** The git-timeout shown by default, when no valid persisted value exists. */
const val DEFAULT_TIMEOUT_SECONDS = 60

/**
 * The git-timeout options in UI order. The settings UI derives its dropdown labels
 * from this list so a timeout can only be persisted at an index the dropdown shows.
 */
fun timeoutOptionsSeconds(): List<Int> = TIMEOUTS

fun dirtyActionToIndex(action: DirtyAction): Int =
    DIRTY_ACTIONS.indexOf(action).takeIf { it >= 0 } ?: 0

fun indexToDirtyAction(index: Int): DirtyAction =
    DIRTY_ACTIONS.getOrElse(index) { DirtyAction.Stash }

/**
 * Maps a persisted dirty-action name back to its enum. Unknown names (hand-edited
 * or corrupted state files) fall back to the safe [DirtyAction.Stash], never
 * silently selecting a more destructive action.
 */
fun dirtyActionFromName(name: String): DirtyAction = when (name) {
    "Skip" -> DirtyAction.Skip
    "Force" -> DirtyAction.Force
    else -> DirtyAction.Stash
}

fun timeoutToIndex(timeoutSeconds: Int): Int =
    TIMEOUTS.indexOf(timeoutSeconds).takeIf { it >= 0 } ?: TIMEOUTS.indexOf(DEFAULT_TIMEOUT_SECONDS)

fun indexToTimeout(index: Int): Int =
    TIMEOUTS.getOrElse(index) { DEFAULT_TIMEOUT_SECONDS }
