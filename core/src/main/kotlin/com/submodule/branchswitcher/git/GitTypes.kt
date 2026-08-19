package com.submodule.branchswitcher.git

import com.submodule.branchswitcher.EnvironmentFailure
import com.submodule.branchswitcher.log.sanitizeDiagnosticText

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
            stderr == "cancelled" -> GitFailureKind.CANCELLED
            stderr == "interrupted" -> GitFailureKind.INTERRUPTED
            stderr.startsWith("timeout after ") -> GitFailureKind.TIMEOUT
            stderr.startsWith("process capacity unavailable after ") -> GitFailureKind.PROCESS_CAPACITY
            stderr.startsWith("failed to start: ") -> GitFailureKind.START_FAILED
            stderr.startsWith("output limit exceeded: ") -> GitFailureKind.OUTPUT_LIMIT
            stderr.startsWith("output capture ") -> GitFailureKind.OUTPUT_CAPTURE
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
