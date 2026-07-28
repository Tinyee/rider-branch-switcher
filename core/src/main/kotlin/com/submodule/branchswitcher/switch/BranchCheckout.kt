package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Selects a local or remote branch and records the completed checkout. */
internal object BranchCheckout {
    data class Result(
        val state: SwitchState,
        val succeeded: Boolean,
        val failure: String? = null,
    )

    fun execute(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
    ): Result {
        val currentBranch = context.git.currentBranch(directory)
        context.log.info("current: ${currentBranch ?: "(detached)"}")

        if (currentBranch == target.branch) {
            context.log.info("already on '${target.branch}', skipping checkout")
            return Result(
                state = state.withSuccessfulCheckout(target.path),
                succeeded = true,
            )
        }

        val checkoutResult = when {
            context.git.localBranchExists(directory, target.branch) ->
                context.git.checkoutExisting(directory, target.branch)

            context.git.remoteBranchExists(directory, target.branch) -> {
                context.log.info("local branch missing, creating from origin/${target.branch}")
                context.git.checkoutFromRemote(directory, target.branch)
            }

            else -> return recoverStashAfterMissingBranch(context, target, directory, state)
        }

        if (!checkoutResult.ok) {
            context.log.warn("[fail] checkout: ${checkoutResult.diagnostic()}")
            return Result(state, succeeded = false, failure = "checkout failed")
        }

        context.log.info("checkout ok")
        return Result(
            state = state.withSuccessfulCheckout(target.path),
            succeeded = true,
        )
    }

    /**
     * A missing target branch must not leave a stash created earlier in the
     * switch hidden from the user.
     */
    private fun recoverStashAfterMissingBranch(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
    ): Result {
        context.log.warn("[fail] branch '${target.branch}' not found locally or on origin")
        val trackedStash = state.trackedStash(target.path)
        if (trackedStash == null) {
            return Result(state, succeeded = false, failure = "branch not found")
        }

        val popResult = context.git.stashPop(directory)
        if (!popResult.ok) {
            context.log.warn("[fail] stash pop also failed: ${popResult.diagnostic()}")
            return Result(
                state,
                succeeded = false,
                failure = "branch not found + stash pop failed",
            )
        }

        context.log.info("stash pop ok (recovered after branch-not-found: $trackedStash)")
        return Result(
            state = state.withoutStash(target.path),
            succeeded = false,
            failure = "branch not found",
        )
    }
}
