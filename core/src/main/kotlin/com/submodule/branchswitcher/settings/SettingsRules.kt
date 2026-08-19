package com.submodule.branchswitcher.settings

import com.submodule.branchswitcher.model.DirtyAction

// Dropdown order in the settings UI. Using a list means the index IS the position,
// so adding an entry appends safely instead of silently shifting existing options.
private val DIRTY_ACTIONS = listOf(DirtyAction.Stash, DirtyAction.Skip, DirtyAction.Force)
private val TIMEOUTS = listOf(30, 60, 120, 300)

fun dirtyActionToIndex(action: DirtyAction): Int =
    DIRTY_ACTIONS.indexOf(action).takeIf { it >= 0 } ?: 0

fun indexToDirtyAction(index: Int): DirtyAction =
    DIRTY_ACTIONS.getOrElse(index) { DirtyAction.Stash }

fun timeoutToIndex(timeoutSeconds: Int): Int =
    TIMEOUTS.indexOf(timeoutSeconds).takeIf { it >= 0 } ?: 1

fun indexToTimeout(index: Int): Int =
    TIMEOUTS.getOrElse(index) { 60 }
