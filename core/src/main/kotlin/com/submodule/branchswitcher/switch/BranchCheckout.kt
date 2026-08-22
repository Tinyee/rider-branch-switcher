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
        val result = executeOnce(context, target, directory, state)
        if (!result.succeeded) {
            val approved = context.approvedCollisionDiscards[target.path].orEmpty()
            if (approved.isNotEmpty()) {
                // Re-query the collision set at checkout time (post-fetch) instead of matching
                // git's stderr text — dir→file conflicts emit a different message, and only a
                // path that is STILL an untracked collision warrants deleting and retrying once.
                val ref = state.frozenTargetSha(target.path) ?: target.branch
                val stillColliding = collidingApproved(context, target, directory, ref)
                if (stillColliding.isNotEmpty()) {
                    context.log.warn("[retry] untracked collision, discarding approved files - ${directory.path}")
                    discardApprovedFiles(directory, stillColliding, context.log, OperationStage.CHECKOUT, target.path)
                    return executeOnce(context, target, directory, state)
                }
            }
        }
        return result
    }

    private fun executeOnce(
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
                discardThenCheckout(context, target, directory, state) {
                    context.git.checkoutExisting(directory, target.branch)
                }

            context.git.remoteBranchExists(directory, target.branch) -> {
                context.log.info("local branch missing, creating from origin/${target.branch}")
                discardThenCheckout(context, target, directory, state) {
                    context.git.checkoutFromRemote(directory, target.branch)
                }
            }

            else -> {
                // A missing target branch must not leave a stash created earlier in the
                // switch hidden from the user: returning a failed checkout keeps state
                // unchanged, so the stash stays tracked. SwitchExecutor restores it at
                // the end of a partial pipeline, or SwitchRunner's recovery applies it
                // after rolling the repositories back.
                return branchMissingFailure(context, target, directory, state)
            }
        }

        if (!checkoutResult.ok) {
            context.log.warn("[fail] checkout - ${directory.path}: ${checkoutResult.diagnostic()}")
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

        // Freeze postcondition: the checked-out HEAD must be the exact revision the collision
        // revalidation deleted against. A mismatch means the target ref moved between the
        // freeze and the checkout (nothing in this pipeline fetches between them), so the
        // deletion may have targeted a slightly different tree — surface it, don't hide it.
        val issues = mutableListOf<OperationIssue>()
        val frozenSha = state.frozenTargetSha(target.path)
        if (frozenSha != null) {
            val head = runCatching { context.git.revParseHead(directory) }.getOrNull()
            if (head != null && head != frozenSha) {
                context.log.warn("[head-moved] ${directory.path}: HEAD=$head frozen=$frozenSha")
                issues += OperationIssue(
                    stage = OperationStage.CHECKOUT,
                    code = OperationIssueCode.HEAD_MOVED,
                    repositoryPath = target.path,
                    diagnostic = "HEAD $head differs from frozen $frozenSha",
                )
            }
        }

        context.log.info("checkout ok")
        return Result(
            state = state.withSuccessfulCheckout(target.path),
            succeeded = true,
            issues = issues,
        )
    }

    /**
     * Revalidates and deletes the approved collision files for [target] immediately before its
     * checkout write, against the frozen revision when one was recorded, else the target branch
     * (identical at this point — nothing fetches between the freeze and the checkout). A delete
     * failure is logged and left to the checkout to surface: if the file still blocks, the write
     * fails with the structured collision error.
     */
    private fun <T> discardThenCheckout(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        state: SwitchState,
        action: () -> T,
    ): T {
        val ref = state.frozenTargetSha(target.path) ?: target.branch
        discardCollidingApproved(context, target, directory, ref, OperationStage.CHECKOUT)
            .forEach { issue ->
                context.log.warn("[discard] ${target.path}: ${issue.diagnostic}")
            }
        return action()
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
