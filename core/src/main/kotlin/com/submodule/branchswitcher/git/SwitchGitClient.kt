package com.submodule.branchswitcher.git

import java.io.File

/** Git operations required by the branch-switch pipeline. */
interface SwitchGitClient : RepositoryStateGitClient, SubmoduleRegistrationQuery {
    /**
     * Cancels the active operation and its currently running git command (if any).
     * Implementations without processes must still acknowledge cancellation explicitly.
     */
    fun cancel()

    /**
     * Write safety requires implementations to identify the repository backing an
     * existing worktree. Re-declared without a body (deliberately abstract) so a
     * write-path implementation cannot inherit the base default and silently run
     * without repository identity — the checkpoint would then fail closed.
     */
    override fun repositoryIdentity(workDir: File): RepositoryIdentity?
    /** Stashes all changes including untracked files (-u). */
    fun stash(workDir: File, message: String): GitResult
    /** Returns the immutable object id currently referenced by refs/stash. */
    fun stashTopOid(workDir: File): String?

    /**
     * Returns the object id of the newest stash whose subject contains [messagePrefix],
     * or null when no entry matches (or the implementation does not support message
     * lookups). Callers fall back to [stashTopOid] when this returns null. The CLI
     * implementation scopes the lookup to the stashes this plugin created so a
     * concurrent external `git stash push` cannot be misapplied.
     */
    fun stashOidByMessage(workDir: File, messagePrefix: String): String? = null

    /** Applies the stash identified by [oid] without removing its recovery backup. */
    fun stashApply(workDir: File, oid: String): GitResult

    /**
     * Drops the stash entry identified by [oid] from `refs/stash`. Called after a
     * successful [stashApply] so successful switches do not accumulate one backup
     * per switch in `git stash list`. Test doubles that never run a real apply may
     * inherit the succeeding default; the CLI implementation overrides this.
     */
    fun stashDrop(workDir: File, oid: String): GitResult =
        GitResult("stash drop", 0, "", "")
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
