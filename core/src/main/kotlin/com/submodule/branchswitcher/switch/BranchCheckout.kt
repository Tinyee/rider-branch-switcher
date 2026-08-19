package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Selects a local or remote branch and records the completed checkout. */
internal object BranchCheckout {
    data class Result(
        val state: SwitchState,
        val succeeded: Boolean,
        val issues: List<OperationIssue> = emptyList(),
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

            else -> return recoverStashAfterMissingBranch(context, target, state)
        }

        if (!checkoutResult.ok) {
            context.log.warn("[fail] checkout: ${checkoutResult.diagnostic()}")
            return Result(
                state,
                succeeded = false,
                issues = listOf(
                    OperationIssue(
                        stage = OperationStage.CHECKOUT,
                        code = OperationIssueCode.CHECKOUT_FAILED,
                        repositoryPath = target.path,
                        diagnostic = checkoutResult.diagnostic(),
                    ),
                ),
            )
        }

        context.log.info("checkout ok")
        return Result(
            state = state.withSuccessfulCheckout(target.path),
            succeeded = true,
        )
    }

    /**
     * A missing target branch must not leave a stash created earlier in the switch
     * hidden from the user. The stash stays tracked here; SwitchExecutor restores it
     * at the end of a partial pipeline, or SwitchRunner's recovery applies it after
     * rolling the repositories back.
     */
    private fun recoverStashAfterMissingBranch(
        context: SwitchContext,
        target: RepoTarget,
        state: SwitchState,
    ): Result {
        context.log.warn("[fail] branch '${target.branch}' not found locally or on origin")
        return Result(state, succeeded = false, issues = listOf(branchMissingIssue(target.path)))
    }

    private fun branchMissingIssue(path: String) = OperationIssue(
        stage = OperationStage.CHECKOUT,
        code = OperationIssueCode.BRANCH_NOT_FOUND,
        repositoryPath = path,
    )
}
