package com.submodule.branchswitcher.git

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
            stderr.startsWith("failed to start: ") -> GitFailureKind.START_FAILED
            else -> GitFailureKind.GIT_FAILED
        }

    /** Compact, bounded failure detail for user-facing logs. */
    fun diagnostic(maxLines: Int = 5, maxChars: Int = 1000): String {
        val detail = stderr.lineSequence().take(maxLines).joinToString("\n").take(maxChars)
        return "[$failureKind] $cmd (exit $exitCode): ${detail.ifEmpty { "no stderr" }}"
    }
}

enum class GitFailureKind { NONE, CANCELLED, INTERRUPTED, TIMEOUT, START_FAILED, GIT_FAILED }

/** A Git read/query failed and cannot be safely interpreted as a normal negative result. */
class GitQueryException(val result: GitResult) : RuntimeException(result.diagnostic())

/** Repository metadata shared by multiple Git workflows. */
interface GitRepositoryQuery {
    /** True when [workDir] is a usable git repository. */
    fun isGitRepo(workDir: File): Boolean = File(workDir, ".git").exists()
    /** Returns the current branch name, or null on detached HEAD. Throws when Git cannot inspect HEAD. */
    fun currentBranch(workDir: File): String?
    /** Returns the SHA of HEAD, or null if the repo has no commits. */
    fun revParseHead(workDir: File): String?
}

/** Read-only Git operations used to detect the current repository state. */
interface RepositoryStateGitClient : GitRepositoryQuery {
    /** True if the working tree has uncommitted changes. Throws when status cannot be inspected. */
    fun isDirty(workDir: File): Boolean
}

/** Git operations required by the branch-switch pipeline. */
interface SwitchGitClient : RepositoryStateGitClient, GitCancellation {
    /** Checks whether refs/heads/<branch> exists (plumbing: show-ref --verify). */
    fun localBranchExists(workDir: File, branch: String): Boolean
    /** Checks whether refs/remotes/origin/<branch> exists (plumbing: show-ref --verify). */
    fun remoteBranchExists(workDir: File, branch: String): Boolean
    /** Stashes all changes including untracked files (-u). */
    fun stash(workDir: File, message: String): GitResult
    /** Pops the latest stash. */
    fun stashPop(workDir: File): GitResult
    /** Runs `git fetch --prune`. */
    fun fetch(workDir: File): GitResult
    /** Checks out an existing local branch by name. */
    fun checkoutExisting(workDir: File, branch: String): GitResult
    /** Resets the current branch and worktree to a checkpoint commit. */
    fun resetHard(workDir: File, revision: String): GitResult =
        GitResult("git reset --hard $revision", 1, "", "resetHard not implemented")
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
interface DeriveGitClient : GitRepositoryQuery {
    /** Tri-state probe for safety gates: true=exists, false=not, null=unknown/error. */
    fun localBranchProbe(workDir: File, branch: String): Boolean? = null
    /** Tri-state probe for safety gates: true=dirty, false=clean, null=unknown/error. */
    fun dirtyProbe(workDir: File): Boolean? = null
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

interface GitCancellation {
    /**
     * Cancels the active operation and its currently running git command (if any).
     * Default is a no-op for test doubles that don't spawn real processes.
     */
    fun cancel() {}
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
