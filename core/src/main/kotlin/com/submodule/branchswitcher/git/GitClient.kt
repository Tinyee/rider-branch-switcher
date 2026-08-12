package com.submodule.branchswitcher.git

import com.submodule.branchswitcher.EnvironmentFailure
import com.submodule.branchswitcher.log.sanitizeDiagnosticText
import java.io.File

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

/** One `.gitmodules` entry with its full project path and immediate parent path. */
data class SubmoduleRegistration(
    val path: String,
    val sectionName: String,
    val parentPath: String,
)

/** Repository metadata shared by multiple Git workflows. */
interface GitRepositoryQuery {
    /** True when [workDir] is a usable git repository. */
    fun isGitRepo(workDir: File): Boolean = File(workDir, ".git").exists()
    /** Returns the current branch name, or null on detached HEAD. Throws when Git cannot inspect HEAD. */
    fun currentBranch(workDir: File): String?
    /** Returns the SHA of HEAD, or null if the repo has no commits. */
    fun revParseHead(workDir: File): String?
    /** Returns stable repository storage and superproject paths, or null when unsupported. */
    fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
    /** Returns the selected fetch remote URL, or null when the repository has no remote. */
    fun remoteUrl(workDir: File): String? = null
    /** Returns the Git executable version and effective per-command timeout when supported. */
    fun runtimeInfo(workDir: File): GitRuntimeInfo? = null

    /**
     * Returns the path of an existing git index lock (`index.lock`) for [workDir],
     * or null when no lock is present or it cannot be resolved. A stale lock
     * makes every git write fail, and `git stash` fails on it silently; surface
     * it as an actionable hint. Defaults to null so callers treat an unknown
     * implementation as "no lock" rather than spuriously blocking.
     */
    fun indexLockFile(workDir: File): String? = null
}

/** Read-only Git operations used to detect the current repository state. */
interface RepositoryStateGitClient : GitRepositoryQuery {
    /** True if the working tree has uncommitted changes. Throws when status cannot be inspected. */
    fun isDirty(workDir: File): Boolean

    /**
     * True when the working tree is dirty and every dirty entry is a submodule
     * change. `git stash` ignores submodules entirely, so this dirt cannot be
     * protected by a superproject stash; callers may proceed without stashing.
     * Untracked and unmerged entries always count as protectable. Throws when
     * status cannot be inspected. Defaults to false so callers fail closed to
     * the stash path.
     */
    fun isSubmoduleOnlyDirty(workDir: File): Boolean = false
}

/** Read-only access to the submodule paths registered by the current worktree graph. */
interface SubmoduleRegistrationQuery {
    /** Returns the complete checked-out `.gitmodules` graph with stable section identity. */
    fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration>
}

/** Git operations required by the branch-switch pipeline. */
interface SwitchGitClient : RepositoryStateGitClient, SubmoduleRegistrationQuery, GitCancellation {
    /** Write safety requires implementations to identify the repository backing an existing worktree. */
    override fun repositoryIdentity(workDir: File): RepositoryIdentity?
    /** Write safety records the selected remote before submodule synchronization. */
    override fun remoteUrl(workDir: File): String?
    /** Checks whether refs/heads/<branch> exists (plumbing: show-ref --verify). */
    fun localBranchExists(workDir: File, branch: String): Boolean
    /** Checks whether refs/remotes/origin/<branch> exists (plumbing: show-ref --verify). */
    fun remoteBranchExists(workDir: File, branch: String): Boolean
    /** Stashes all changes including untracked files (-u). */
    fun stash(workDir: File, message: String): GitResult
    /** Returns the immutable object id currently referenced by refs/stash. */
    fun stashTopOid(workDir: File): String?
    /** Applies the stash identified by [oid] without removing its recovery backup. */
    fun stashApply(workDir: File, oid: String): GitResult
    /** Runs `git fetch --prune`. */
    fun fetch(workDir: File): GitResult
    /** Checks out an existing local branch by name. */
    fun checkoutExisting(workDir: File, branch: String): GitResult
    /** Resets the current branch and worktree to a checkpoint commit. */
    fun resetHard(workDir: File, revision: String): GitResult
    /** Creates a local branch from origin/<branch> and checks it out. */
    fun checkoutFromRemote(workDir: File, branch: String): GitResult
    /** Pulls with --ff-only from origin for the given branch. */
    fun pullFf(workDir: File, branch: String): GitResult
    /** Runs `git submodule sync --recursive`. */
    fun submoduleSync(gitRoot: File): GitResult
    /** Runs `git submodule update --init --recursive -- <path>`. */
    fun submoduleInitPath(gitRoot: File, path: String): GitResult
}

/** Git operations required by derive-branch preflight, execution, and rollback. */
interface DeriveGitClient : GitRepositoryQuery, SubmoduleRegistrationQuery {
    /** Write safety requires implementations to identify the repository backing an existing worktree. */
    override fun repositoryIdentity(workDir: File): RepositoryIdentity?
    /** Probe failures must throw; callers fail closed at the workflow boundary. */
    fun localBranchProbe(workDir: File, branch: String): Boolean
    /** Probe failures must throw; callers fail closed at the workflow boundary. */
    fun dirtyProbe(workDir: File): Boolean
    /** Creates a new branch from current HEAD and checks it out. */
    fun checkoutNewBranch(workDir: File, branch: String): GitResult
    /** Checks out an existing local branch or commit. */
    fun checkoutExisting(workDir: File, branch: String): GitResult
    /** Safely deletes a local branch (`git branch -d`). Fails if branch has unmerged changes. */
    fun deleteBranch(workDir: File, branch: String): GitResult
}

/** Read-only Git operations used while editing and discovering preset targets. */
interface PresetDiscoveryGitClient : GitRepositoryQuery {
    /**
     * Lists all branches (local + remote), deduplicated and sorted.
     * Filters out the remote HEAD entry and strips the remote prefix.
     */
    fun listAllBranches(workDir: File): List<String>
    /** Recursively parses .gitmodules to list all submodule paths, including nested ones. */
    fun listSubmodulePaths(gitRoot: File): List<String>
}

/** Read-only Git operations used by the pre-switch preview. */
interface SwitchPreflightGitClient : GitRepositoryQuery {
    /** Number of dirty files (0 = clean). Uses `git status --porcelain`. */
    fun dirtyFileCount(workDir: File): Int
    fun localBranchExists(workDir: File, branch: String): Boolean
    fun remoteBranchExists(workDir: File, branch: String): Boolean
}

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

/** Optional optimized capability; callers retain a compatible per-query fallback. */
interface RepositoryStateBatchGitClient {
    fun inspectRepositoryState(workDir: File): GitRepositoryInspection
}

/** Optional optimized capability for fail-closed switch preview inspection. */
interface SwitchPreflightBatchGitClient {
    fun inspectPreflight(workDir: File, targetBranches: Set<String>): GitRepositoryInspection
}

interface GitCancellation {
    /**
     * Cancels the active operation and its currently running git command (if any).
     * Implementations without processes must still acknowledge cancellation explicitly.
     */
    fun cancel()
}

/** All workflow capabilities exposed by a concrete Git implementation or operation session. */
interface GitWorkflowClient :
    SwitchGitClient,
    DeriveGitClient,
    PresetDiscoveryGitClient,
    SwitchPreflightGitClient

/**
 * Isolated cancellable Git view used by one background operation.
 *
 * Closing a session releases its process ownership; callers must not reuse it.
 */
interface GitOperationSession : GitWorkflowClient, AutoCloseable

/** Factory boundary that gives each background write its own operation session. */
interface GitOperationProvider {
    fun openOperation(): GitOperationSession
}

private class DelegatingGitOperationSession(
    private val client: GitClient,
) : GitOperationSession, GitWorkflowClient by client {

    override fun cancel() = client.cancel()

    override fun close() = Unit
}

/**
 * Aggregate implementation boundary. Consumers should depend on a workflow interface above.
 *
 * Implementations: [com.submodule.branchswitcher.git.GitOps] (CLI via ProcessBuilder).
 *
 * Key semantics:
 * - [currentBranch] returns null on detached HEAD
 * - [checkoutExisting] switches to an existing local branch
 * - [checkoutFromRemote] creates a local branch tracking origin/<branch>
 * - [checkoutNewBranch] creates a new branch from current HEAD
 * - [fetch] always passes --prune
 * - [submoduleSync] uses --recursive
 * - [stash] pushes with -u (includes untracked files)
 */
interface GitClient :
    GitWorkflowClient,
    GitOperationProvider {
    /**
     * Compatibility adapter for test doubles and alternate implementations.
     * [GitOps] overrides this with a cancellation-isolated session.
     */
    override fun openOperation(): GitOperationSession {
        return DelegatingGitOperationSession(this)
    }
}
