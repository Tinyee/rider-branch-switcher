package com.submodule.branchswitcher.git

import java.io.File

/**
 * Git operations required by derive-branch preflight, execution, and rollback.
 *
 * Derive reuses the shared read-only surface ([RepositoryStateGitClient] for dirty
 * state and branch existence) rather than duplicating it under "probe" names:
 * [isDirty] and [localBranchExists] already throw on Git failures, which is the
 * fail-closed contract callers rely on.
 */
interface DeriveGitClient : RepositoryStateGitClient, SubmoduleRegistrationQuery {
    /**
     * Write safety requires implementations to identify the repository backing an
     * existing worktree. Deliberately abstract (see [SwitchGitClient.repositoryIdentity]).
     */
    override fun repositoryIdentity(workDir: File): RepositoryIdentity?
    /** Creates a new branch from current HEAD and checks it out. */
    fun checkoutNewBranch(workDir: File, branch: String): GitResult
    /** Checks out an existing local branch or commit. */
    fun checkoutExisting(workDir: File, branch: String): GitResult
    /** Safely deletes a local branch (`git branch -d`). Fails if branch has unmerged changes. */
    fun deleteBranch(workDir: File, branch: String): GitResult
}
