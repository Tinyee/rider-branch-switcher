package com.submodule.branchswitcher.git

import java.io.File

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
