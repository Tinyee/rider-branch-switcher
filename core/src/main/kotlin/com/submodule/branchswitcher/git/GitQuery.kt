package com.submodule.branchswitcher.git

import java.io.File

/** Repository metadata shared by multiple Git workflows. */
interface GitRepositoryQuery {
    /** True when [workDir] is a usable git repository. */
    fun isGitRepo(workDir: File): Boolean = File(workDir, ".git").exists()
    /** Returns the current branch name, or null on detached HEAD. Throws when Git cannot inspect HEAD. */
    fun currentBranch(workDir: File): String?
    /** Returns the SHA of HEAD, or null if the repo has no commits. */
    fun revParseHead(workDir: File): String?

    /**
     * Atomically reads HEAD and the current branch from one git invocation so a
     * concurrent HEAD move can never pair a SHA with the wrong branch name.
     * Returns null when the implementation does not support the single-query read;
     * callers use [resolveHeadAndBranch] for the shared atomic-read-then-fallback.
     */
    fun headAndBranch(workDir: File): HeadAndBranch? = null
    /** Returns stable repository storage and superproject paths, or null when unsupported. */
    fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
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

/** Optional optimized capability; callers retain a compatible per-query fallback. */
interface RepositoryStateBatchGitClient {
    fun inspectRepositoryState(workDir: File): GitRepositoryInspection
}

/** Optional optimized capability for fail-closed switch preview inspection. */
interface SwitchPreflightBatchGitClient {
    fun inspectPreflight(workDir: File, targetBranches: Set<String>): GitRepositoryInspection
}

/**
 * Reads HEAD and the current branch atomically when the client supports it,
 * falling back to separate reads otherwise. Returns null only when HEAD
 * cannot be resolved. Prefer this over calling [headAndBranch] +
 * [revParseHead] + [currentBranch] so callers share one fallback contract.
 *
 * Implemented as an extension (not an interface default method) so the reads
 * dispatch to the receiver's overrides: an interface default would be forwarded
 * wholesale by `by`-delegation wrappers such as [GitWorkflowClient] sessions,
 * pairing a delegate's SHA with a wrapper's branch override.
 */
fun GitRepositoryQuery.resolveHeadAndBranch(workDir: File): HeadAndBranch? {
    val atomic = headAndBranch(workDir)
    val sha = atomic?.sha ?: revParseHead(workDir) ?: return null
    return HeadAndBranch(sha, atomic?.branch ?: currentBranch(workDir))
}
