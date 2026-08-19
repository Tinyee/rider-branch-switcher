package com.submodule.branchswitcher.switch

/** The state and issues produced by [restoreTrackedStashes]. */
data class StashRestoreResult(
    val state: SwitchState,
    val issues: List<OperationIssue>,
    /** True when the restore loop stopped because the caller's cancel flag was set. */
    val interrupted: Boolean = false,
)
