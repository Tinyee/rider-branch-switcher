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

            else -> return recoverStashAfterMissingBranch(context, target, directory, state)
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
            return Result(state, succeeded = false, issues = listOf(branchMissingIssue(target.path)))
        }
        if (trackedStash.oid == null) {
            return Result(
                state,
                succeeded = false,
                issues = listOf(
                    branchMissingIssue(target.path),
                    OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                        repositoryPath = target.path,
                        severity = OperationIssueSeverity.ERROR,
                    ),
                ),
            )
        }

        val applyResult = context.git.stashApply(directory, trackedStash.oid)
        if (!applyResult.ok) {
            context.log.warn("[fail] stash apply also failed: ${applyResult.diagnostic()}")
            return Result(
                state,
                succeeded = false,
                issues = listOf(
                    branchMissingIssue(target.path),
                    OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.STASH_RESTORE_FAILED,
                        repositoryPath = target.path,
                        diagnostic = applyResult.diagnostic(),
                    ),
                ),
            )
        }

        context.log.info(
            "stash apply ok; recovery backup retained " +
                "(recovered after branch-not-found: ${trackedStash.message})",
        )
        return Result(
            state = state.withRestoredStashBackup(target.path),
            succeeded = false,
            issues = listOf(branchMissingIssue(target.path)),
        )
    }

    private fun branchMissingIssue(path: String) = OperationIssue(
        stage = OperationStage.CHECKOUT,
        code = OperationIssueCode.BRANCH_NOT_FOUND,
        repositoryPath = path,
    )
}
