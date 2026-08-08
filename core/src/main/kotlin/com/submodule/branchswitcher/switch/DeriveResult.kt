package com.submodule.branchswitcher.switch

/** Checkpoint recorded before deriving a branch so created branches can be rolled back safely. */
data class DeriveCheckpointEntry(
    val sha: String,
    val branch: String?,
    val repositoryId: String? = null,
)

enum class DeriveRepositoryStatus {
    SUCCEEDED,
    BRANCH_EXISTS,
    SKIPPED,
    DIRTY,
    BRANCH_MISMATCH,
    PREFLIGHT_FAILED,
    CHECKPOINT_FAILED,
    FAILED,
}

/** One authoritative derive outcome per repository instead of parallel category lists. */
data class DeriveRepositoryOutcome(
    val repositoryPath: String,
    val status: DeriveRepositoryStatus,
    val issue: OperationIssue? = null,
)

/** Paths whose rollback still needs to be retried after a failure or cancellation. */
data class DeriveRollbackResult(
    val pendingPaths: List<String>,
) {
    val allCompleted: Boolean get() = pendingPaths.isEmpty()
}

data class DeriveResult(
    val outcomes: List<DeriveRepositoryOutcome>,
    val checkpoint: Map<String, DeriveCheckpointEntry>,
    val cancelled: Boolean = false,
) {
    val succeeded: List<String> get() = pathsWith(DeriveRepositoryStatus.SUCCEEDED)
    val branchExists: List<String> get() = pathsWith(DeriveRepositoryStatus.BRANCH_EXISTS)
    val skipped: List<String> get() = pathsWith(DeriveRepositoryStatus.SKIPPED)
    val dirty: List<String> get() = pathsWith(DeriveRepositoryStatus.DIRTY)
    val branchMismatch: List<String> get() = pathsWith(DeriveRepositoryStatus.BRANCH_MISMATCH)
    val preflightError: List<String> get() = pathsWith(DeriveRepositoryStatus.PREFLIGHT_FAILED)
    val checkpointFailed: List<String> get() = pathsWith(DeriveRepositoryStatus.CHECKPOINT_FAILED)
    val failedOutcomes: List<DeriveRepositoryOutcome>
        get() = outcomes.filter { it.status == DeriveRepositoryStatus.FAILED }
    val issues: List<OperationIssue> get() = outcomes.mapNotNull(DeriveRepositoryOutcome::issue)

    val allOk: Boolean get() = !cancelled && succeeded.isNotEmpty() && failedOutcomes.isEmpty()
    val preflightBlocked: Boolean get() = !cancelled && succeeded.isEmpty() &&
        outcomes.any { it.status in PREFLIGHT_BLOCKING_STATUSES }
    val checkpointBlocked: Boolean get() = !cancelled && succeeded.isEmpty() && !preflightBlocked &&
        checkpointFailed.isNotEmpty()
    val actualCreated: Int get() = succeeded.size

    private fun pathsWith(status: DeriveRepositoryStatus): List<String> =
        outcomes.filter { it.status == status }.map(DeriveRepositoryOutcome::repositoryPath)

    private companion object {
        val PREFLIGHT_BLOCKING_STATUSES = setOf(
            DeriveRepositoryStatus.BRANCH_EXISTS,
            DeriveRepositoryStatus.SKIPPED,
            DeriveRepositoryStatus.DIRTY,
            DeriveRepositoryStatus.BRANCH_MISMATCH,
            DeriveRepositoryStatus.PREFLIGHT_FAILED,
        )
    }
}
