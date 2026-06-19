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
    /** True when [workDir] is a usable git repository. */
    fun isGitRepo(workDir: File): Boolean = File(workDir, ".git").exists()
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
    /** Tri-state probe for safety gates: true=exists, false=not, null=unknown/error. Implementations must opt in. */
    fun localBranchProbe(workDir: File, branch: String): Boolean? = null
    /** Tri-state probe for safety gates: true=dirty, false=clean, null=unknown/error. Implementations must opt in. */
    fun dirtyProbe(workDir: File): Boolean? = null
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
    /** Recursively parses .gitmodules to list all submodule paths, including nested ones. */
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
    /** Safely deletes a local branch (`git branch -d`). Fails if branch has unmerged changes. */
    fun deleteBranch(workDir: File, branch: String): GitResult
    /** Starts a cancellable multi-command operation and clears stale cancellation state. */
    fun beginOperation() {}
    /**
     * Cancels the active operation and its currently running git command (if any).
     * Commands started before [endOperation] should fail without spawning a process.
     * Default is a no-op for test doubles that don't spawn real processes.
     */
    fun cancel() {}
    /** Ends the active cancellable operation and clears its cancellation state. */
    fun endOperation() {}
}
