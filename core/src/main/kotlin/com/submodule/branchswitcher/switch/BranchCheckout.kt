package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Selects a local or remote branch and records the completed checkout. */
internal object BranchCheckout {
    data class Result(
        val state: SwitchState,
        val succeeded: Boolean,
        val issues: List<OperationIssue> = emptyList(),
    )

    /**
     * One checkout attempt. [checkoutFailure] is set only when a checkout command ran and
     * failed, so the retry in [execute] can gate on a plain git failure instead of a
     * termination or process-level failure.
     */
    private data class Attempt(
        val result: Result,
        val checkoutFailure: GitResult? = null,
    )

    fun execute(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
    ): Result {
        val first = executeOnce(context, target, directory, state)
        val firstFailure = first.checkoutFailure
        if (first.result.succeeded || firstFailure == null || firstFailure.failureKind != GitFailureKind.GIT_FAILED) {
            return first.result
        }
        val approved = context.approvedCollisionDiscards[target.path].orEmpty()
        if (approved.isEmpty()) return first.result
        // A collision file may regenerate between the pre-stash step and the checkout (e.g.
        // Unity re-emits a .meta). Only a path that is STILL an untracked collision warrants
        // isolating it (round+1) and retrying once.
        val stillColliding = approvedCollisionPaths(context, target, directory)
        if (stillColliding.isEmpty()) return first.result
        context.log.warn("[retry] untracked collision, isolating approved files - ${directory.path}")
        when (val stash = stashApprovedCollisions(
            context, target, directory, first.result.state, OperationStage.CHECKOUT,
        )) {
            is ApprovedStashOutcome.Blocked -> {
                // The regenerated collision could not be isolated, so a second checkout would
                // fail identically; report the isolation failure instead of discarding the
                // first checkout's diagnostic in a doomed retry.
                context.log.warn("[retry] ${target.path}: ${stash.issue.diagnostic}")
                return first.result.copy(issues = first.result.issues + stash.issue)
            }
            is ApprovedStashOutcome.Proceed ->
                return executeOnce(context, target, directory, stash.state).result
        }
    }

    private fun executeOnce(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
    ): Attempt {
        val outcome = RepositoryCheckout(context.git, context.log).checkout(directory, target.branch)
        when (outcome) {
            is RepositoryCheckoutOutcome.BranchMissing ->
                // A missing target branch must not leave a stash created earlier in the
                // switch hidden from the user: returning a failed checkout keeps state
                // unchanged, so the stash stays tracked. SwitchExecutor restores it at
                // the end of a partial pipeline, or SwitchRunner's recovery applies it
                // after rolling the repositories back.
                return Attempt(result = branchMissingFailure(context, target, directory, state))
            is RepositoryCheckoutOutcome.AlreadyOnTarget ->
                context.log.info("already on '${target.branch}', skipping checkout")
            is RepositoryCheckoutOutcome.CheckedOut -> if (!outcome.result.ok) {
                context.log.warn("[fail] checkout - ${directory.path}: ${outcome.result.diagnostic()}")
                return Attempt(
                    result = Result(
                        state,
                        succeeded = false,
                        issues = listOf(
                            OperationIssue(
                                stage = OperationStage.CHECKOUT,
                                code = OperationIssueCode.CHECKOUT_FAILED,
                                repositoryPath = target.path,
                                diagnostic = outcome.result.diagnostic(),
                            ),
                        ),
                    ),
                    checkoutFailure = outcome.result,
                )
            }
        }
        context.log.info("checkout ok")
        return Attempt(
            result = Result(
                state = state.withSuccessfulCheckout(target.path),
                succeeded = true,
            ),
        )
    }

    /** Records the structured failure for a target whose branch exists neither locally nor on origin. */
    private fun branchMissingFailure(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
    ): Result {
        context.log.warn("[fail] branch '${target.branch}' not found locally or on origin - ${directory.path}")
        return Result(state, succeeded = false, issues = listOf(branchMissingIssue(target.path)))
    }

    private fun branchMissingIssue(path: String) = OperationIssue(
        stage = OperationStage.CHECKOUT,
        code = OperationIssueCode.BRANCH_NOT_FOUND,
        repositoryPath = path,
    )
}
