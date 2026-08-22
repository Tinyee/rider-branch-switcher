package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import java.io.File

/** Outcome of one [RepositoryCheckout.checkout] selection. */
sealed interface RepositoryCheckoutOutcome {
    /** The repository is already on the target branch; no checkout command ran. */
    data class AlreadyOnTarget(val currentBranch: String?) : RepositoryCheckoutOutcome

    /** A checkout command ran; [result] reports whether it succeeded. */
    data class CheckedOut(val result: GitResult) : RepositoryCheckoutOutcome

    /** The target branch exists neither locally nor on the remote. */
    object BranchMissing : RepositoryCheckoutOutcome
}

/**
 * Shared checkout selection for the pipeline and single-repo switches: skip an already-on-target
 * repository, prefer the local branch over the remote one, classify a missing branch. Dirty,
 * topology, and lock gating stay at the call sites.
 */
class RepositoryCheckout(
    private val git: SwitchGitClient,
    private val log: AppLogger,
) {
    fun checkout(directory: File, branch: String): RepositoryCheckoutOutcome {
        val current = git.currentBranch(directory)
        log.info("current: ${current ?: "(detached)"}")
        if (current == branch) return RepositoryCheckoutOutcome.AlreadyOnTarget(current)
        if (git.localBranchExists(directory, branch)) {
            return RepositoryCheckoutOutcome.CheckedOut(git.checkoutExisting(directory, branch))
        }
        if (git.remoteBranchExists(directory, branch)) {
            log.info("local branch missing, creating from origin/$branch")
            return RepositoryCheckoutOutcome.CheckedOut(git.checkoutFromRemote(directory, branch))
        }
        return RepositoryCheckoutOutcome.BranchMissing
    }
}
