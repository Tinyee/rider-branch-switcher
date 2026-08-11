package com.submodule.branchswitcher.switch

enum class OperationStage {
    CHECKPOINT,
    DIRTY_HANDLING,
    FETCH,
    CHECKOUT,
    PULL,
    SUBMODULE_SYNC,
    SUBMODULE_INIT,
    TOPOLOGY,
    PREFLIGHT,
    DERIVE,
    STASH_RESTORE,
    RECOVERY,
}

enum class OperationIssueCode {
    CHECKPOINT_UNAVAILABLE,
    GIT_QUERY_FAILED,
    WORKTREE_DIRTY,
    STASH_FAILED,
    STASH_IDENTITY_UNAVAILABLE,
    STASH_REPOSITORY_UNAVAILABLE,
    STASH_RESTORE_FAILED,
    FETCH_FAILED,
    MAIN_REPOSITORY_UNAVAILABLE,
    MAIN_CHECKOUT_REQUIRED,
    INDEX_LOCK_BLOCKING,
    BRANCH_NOT_FOUND,
    CHECKOUT_FAILED,
    PULL_FAILED,
    SUBMODULE_SYNC_FAILED,
    SUBMODULE_NOT_REGISTERED,
    SUBMODULE_INIT_DECLINED,
    SUBMODULE_INIT_FAILED,
    SUBMODULE_DIRECTORY_MISSING,
    SUBMODULE_REPOSITORY_MISSING,
    REPOSITORY_IDENTITY_UNAVAILABLE,
    REPOSITORY_IDENTITY_CHANGED,
    REPOSITORY_REMOTE_CHANGED,
    BRANCH_ALREADY_EXISTS,
    REPOSITORY_MISSING,
    BRANCH_MISMATCH,
    PREFLIGHT_FAILED,
    DERIVE_CHECKPOINT_FAILED,
    BRANCH_CREATE_FAILED,
    RECOVERY_SESSION_UNAVAILABLE,
    RECOVERY_FAILED,
    STEP_FAILED,
}

enum class OperationIssueSeverity { WARNING, ERROR }

/** Stable domain diagnostic. Presentation text and retry actions are selected from [code]. */
data class OperationIssue(
    val stage: OperationStage,
    val code: OperationIssueCode,
    val repositoryPath: String? = null,
    val severity: OperationIssueSeverity = OperationIssueSeverity.WARNING,
    val diagnostic: String? = null,
)
