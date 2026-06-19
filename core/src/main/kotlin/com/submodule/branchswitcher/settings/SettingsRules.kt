package com.submodule.branchswitcher.settings

import com.submodule.branchswitcher.model.DirtyAction

fun dirtyActionToIndex(action: DirtyAction): Int = when (action) {
    DirtyAction.Stash -> 0
    DirtyAction.Skip -> 1
    DirtyAction.Force -> 2
}

fun indexToDirtyAction(index: Int): DirtyAction = when (index) {
    1 -> DirtyAction.Skip
    2 -> DirtyAction.Force
    else -> DirtyAction.Stash
}

fun timeoutToIndex(timeoutSeconds: Int): Int = when (timeoutSeconds) {
    30 -> 0
    120 -> 2
    300 -> 3
    else -> 1
}

fun indexToTimeout(index: Int): Int = when (index) {
    0 -> 30
    2 -> 120
    3 -> 300
    else -> 60
}
