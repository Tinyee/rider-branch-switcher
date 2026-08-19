package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import java.io.File

/** Raised when a write is about to start while the repository has an index lock. */
internal class IndexLockBlockedException(
    val repositoryPath: String,
    val lockPath: String,
) : RuntimeException(indexLockBlockedDiagnostic(lockPath))

/**
 * Rechecks the lock immediately before every Git operation that can mutate repository state.
 * The initial executor preflight remains responsible for the user-facing all-target check;
 * this wrapper closes the check-then-act gap for races that happen afterwards.
 */
internal class WriteGuardGitClient(
    private val delegate: SwitchGitClient,
    private val repositoryPath: (File) -> String,
) : SwitchGitClient by delegate {

    internal fun inspectRepositoryStateIfAvailable(workDir: File): GitRepositoryInspection? =
        (delegate as? RepositoryStateBatchGitClient)?.inspectRepositoryState(workDir)

    override fun stash(workDir: File, message: String): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.stash(workDir, message) }

    override fun stashApply(workDir: File, oid: String): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.stashApply(workDir, oid) }

    override fun fetch(workDir: File): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.fetch(workDir) }

    override fun checkoutExisting(workDir: File, branch: String): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.checkoutExisting(workDir, branch) }

    override fun resetHard(workDir: File, revision: String): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.resetHard(workDir, revision) }

    override fun checkoutFromRemote(
        workDir: File,
        branch: String,
    ): com.submodule.branchswitcher.git.GitResult = guarded(workDir) {
        delegate.checkoutFromRemote(workDir, branch)
    }

    override fun pullFf(workDir: File, branch: String): com.submodule.branchswitcher.git.GitResult =
        guarded(workDir) { delegate.pullFf(workDir, branch) }

    override fun submoduleSync(gitRoot: File): com.submodule.branchswitcher.git.GitResult =
        guarded(gitRoot) { delegate.submoduleSync(gitRoot) }

    override fun submoduleInitPath(
        gitRoot: File,
        path: String,
    ): com.submodule.branchswitcher.git.GitResult = guarded(gitRoot) {
        delegate.submoduleInitPath(gitRoot, path)
    }

    override fun stashDrop(
        workDir: File,
        oid: String,
    ): com.submodule.branchswitcher.git.GitResult = guarded(workDir) {
        delegate.stashDrop(workDir, oid)
    }

    private fun <T> guarded(workDir: File, action: () -> T): T {
        val lock = delegate.indexLockFile(workDir)
        if (lock != null) throw IndexLockBlockedException(repositoryPath(workDir), lock)
        return action()
    }
}
