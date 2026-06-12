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
}

/**
 * Abstraction over git operations, enabling mock-based unit testing.
 *
 * Implementations: [com.submodule.branchswitcher.git.GitOps] (CLI via GeneralCommandLine).
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
interface GitClient {
    /** Returns the current branch name, or null on detached HEAD. */
    fun currentBranch(workDir: File): String?
    /** True if the working tree has uncommitted changes. */
    fun isDirty(workDir: File): Boolean
    /** Number of dirty files (0 = clean). Uses `git status --porcelain`. */
    fun dirtyFileCount(workDir: File): Int
    /** Stashes all changes including untracked files (-u). */
    fun stash(workDir: File, message: String): GitResult
    /** Runs `git fetch --prune`. */
    fun fetch(workDir: File): GitResult
    /** Checks whether refs/heads/<branch> exists (plumbing: show-ref --verify). */
    fun localBranchExists(workDir: File, branch: String): Boolean
    /** Checks whether refs/remotes/origin/<branch> exists (plumbing: show-ref --verify). */
    fun remoteBranchExists(workDir: File, branch: String): Boolean
    /** Checks out an existing local branch by name. */
    fun checkoutExisting(workDir: File, branch: String): GitResult
    /** Creates a local branch from origin/<branch> and checks it out. */
    fun checkoutFromRemote(workDir: File, branch: String): GitResult
    /** Pulls with --ff-only from origin for the given branch. */
    fun pullFf(workDir: File, branch: String): GitResult
    /** Runs `git submodule sync --recursive`. */
    fun submoduleSync(gitRoot: File): GitResult
    /** Runs `git submodule update --init -- <path>`. */
    fun submoduleInitPath(gitRoot: File, path: String): GitResult
    /** Parses .gitmodules to list submodule paths. */
    fun listSubmodulePaths(gitRoot: File): List<String>
    /**
     * Lists all branches (local + remote), deduplicated and sorted.
     * Filters out `origin/HEAD` entries and strips `origin/` prefix.
     */
    fun listAllBranches(workDir: File): List<String>
    /** Returns the SHA of HEAD, or null if the repo has no commits. */
    fun revParseHead(workDir: File): String?
    /** Pops the latest stash. */
    fun stashPop(workDir: File): GitResult
    /** Creates a new branch from current HEAD and checks it out. */
    fun checkoutNewBranch(workDir: File, branch: String): GitResult
    /**
     * Signals cancellation of the currently running git command (if any).
     * Implementations should terminate the underlying process promptly.
     * Default is a no-op for test doubles that don't spawn real processes.
     */
    fun cancel() {}
}
