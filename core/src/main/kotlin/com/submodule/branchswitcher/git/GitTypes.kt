package com.submodule.branchswitcher.git

import com.submodule.branchswitcher.log.EnvironmentFailure
import com.submodule.branchswitcher.log.sanitizeDiagnosticText

/**
 * Cross-layer stderr sentinels: the plugin-layer [GitProcessRunner] emits these as
 * `stderr` and [GitResult.failureKind] classifies them back into a [GitFailureKind].
 * They are the one string contract between the two modules — never change a value
 * without updating the emitter and the locking tests (GitResultTest / GitProcessRunnerTest).
 */
const val GIT_STDERR_CANCELLED = "cancelled"
const val GIT_STDERR_INTERRUPTED = "interrupted"
const val GIT_STDERR_TIMEOUT_PREFIX = "timeout after "
const val GIT_STDERR_CAPACITY_PREFIX = "process capacity unavailable after "
const val GIT_STDERR_START_FAILED_PREFIX = "failed to start: "
const val GIT_STDERR_OUTPUT_LIMIT_PREFIX = "output limit exceeded: "
const val GIT_STDERR_OUTPUT_CAPTURE_PREFIX = "output capture "

/** Result of a git CLI command. [ok] is true when exitCode == 0. */
data class GitResult(
    val cmd: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
    val failureKind: GitFailureKind
        get() = when {
            ok -> GitFailureKind.NONE
            stderr == GIT_STDERR_CANCELLED -> GitFailureKind.CANCELLED
            stderr == GIT_STDERR_INTERRUPTED -> GitFailureKind.INTERRUPTED
            stderr.startsWith(GIT_STDERR_TIMEOUT_PREFIX) -> GitFailureKind.TIMEOUT
            stderr.startsWith(GIT_STDERR_CAPACITY_PREFIX) -> GitFailureKind.PROCESS_CAPACITY
            stderr.startsWith(GIT_STDERR_START_FAILED_PREFIX) -> GitFailureKind.START_FAILED
            stderr.startsWith(GIT_STDERR_OUTPUT_LIMIT_PREFIX) -> GitFailureKind.OUTPUT_LIMIT
            stderr.startsWith(GIT_STDERR_OUTPUT_CAPTURE_PREFIX) -> GitFailureKind.OUTPUT_CAPTURE
            else -> GitFailureKind.GIT_FAILED
        }

    /** Compact, bounded failure detail for user-facing logs. */
    fun diagnostic(maxLines: Int = 5, maxChars: Int = 1000): String {
        val bounded = stderr.lineSequence().take(maxLines).joinToString("\n").take(maxChars)
        val detail = sanitizeDiagnosticText(bounded)
        return "[$failureKind] ${sanitizeDiagnosticText(cmd)} (exit $exitCode): ${detail.ifEmpty { "no stderr" }}"
    }
}

enum class GitFailureKind {
    NONE,
    CANCELLED,
    INTERRUPTED,
    TIMEOUT,
    PROCESS_CAPACITY,
    START_FAILED,
    OUTPUT_LIMIT,
    OUTPUT_CAPTURE,
    GIT_FAILED,
}

/**
 * True when a command was terminated mid-run (cancelled, interrupted, or timed out)
 * rather than failing on its own. Terminated writes may have left partial side effects
 * (a "torn" stash push, an in-flight checkout), so callers treat them as a stop signal
 * instead of a real failure: the write is marked attempted (at-most-once) and never
 * automatically retried — only a failure proven to occur before Git started (an
 * index.lock block) stays retryable.
 */
val GitFailureKind.isTermination: Boolean
    get() = this == GitFailureKind.CANCELLED || this == GitFailureKind.INTERRUPTED || this == GitFailureKind.TIMEOUT

/** A Git read/query failed and cannot be safely interpreted as a normal negative result. */
class GitQueryException(val result: GitResult) : RuntimeException(result.diagnostic()), EnvironmentFailure

/** The submodule topology cannot be read because the project root path cannot be resolved. */
class SubmoduleDiscoveryException(message: String, cause: Throwable) : RuntimeException(message, cause), EnvironmentFailure

/** Stable repository metadata used to reject replaced worktrees during writes and recovery. */
data class RepositoryIdentity(
    val gitDirectory: String,
    val superprojectRoot: String?,
)

/** Runtime details needed to reproduce Git process behavior from diagnostic logs. */
data class GitRuntimeInfo(
    val version: String,
    val timeoutSeconds: Int,
)

/** One `.gitmodules` entry with its full project path, immediate parent path, and declared URL. */
data class SubmoduleRegistration(
    val path: String,
    val sectionName: String,
    val parentPath: String,
    val url: String? = null,
)

/** One bounded read of repository metadata used by status and preflight screens. */
data class GitRepositoryInspection(
    val isGitRepository: Boolean,
    val currentBranch: String?,
    val head: String?,
    val dirtyFileCount: Int,
    val submoduleOnlyDirty: Boolean = false,
    val localBranches: Set<String> = emptySet(),
    val remoteBranches: Set<String> = emptySet(),
)

/** HEAD SHA and current branch read atomically from a single git invocation. */
data class HeadAndBranch(
    val sha: String?,
    val branch: String?,
)
