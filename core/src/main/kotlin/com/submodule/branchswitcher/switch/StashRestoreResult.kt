package com.submodule.branchswitcher.switch

/** The state and issues produced by [restoreTrackedStashes]. */
data class StashRestoreResult(
    val state: SwitchState,
    val issues: List<OperationIssue>,
)
