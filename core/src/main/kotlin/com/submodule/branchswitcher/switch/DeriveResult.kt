package com.submodule.branchswitcher.switch

/** Checkpoint recorded before deriving a branch so created branches can be rolled back safely. */
data class DeriveCheckpointEntry(
    val sha: String,
    val branch: String?,
)

/** Pure result DTO for derive-branch execution and notification decisions. */
data class DeriveResult(
    val succeeded: List<String>,
    val branchExists: List<String>,
    val skipped: List<String>,
    val dirty: List<String>,
    val branchMismatch: List<String>,
    val preflightError: List<String>,
    val checkpointFailed: List<String>,
    val failed: Map<String, String>,
    val checkpoint: Map<String, DeriveCheckpointEntry>,
    val cancelled: Boolean = false,
) {
    val allOk: Boolean get() = !cancelled && succeeded.isNotEmpty() && failed.isEmpty()
    val preflightBlocked: Boolean get() = !cancelled && succeeded.isEmpty() && failed.isEmpty() &&
        (branchExists.isNotEmpty() || skipped.isNotEmpty() || dirty.isNotEmpty() ||
            branchMismatch.isNotEmpty() || preflightError.isNotEmpty())
    val checkpointBlocked: Boolean get() = !cancelled && succeeded.isEmpty() && failed.isEmpty() &&
        !preflightBlocked && checkpointFailed.isNotEmpty()
    val actualCreated: Int get() = succeeded.size
}
