package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Selects a local or remote branch and records the completed checkout. */
internal object BranchCheckout {
    /** Git's exact refusal message when untracked files would be overwritten by a checkout. */
    private const val UNTRACKED_OVERWRITE_HINT = "untracked working tree files would be overwritten"

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
        if (!result.succeeded && shouldRetryAfterDiscard(context, target, result)) {
            // The approved collision files reappeared since the discard step (Unity
            // regenerates .meta files mid-switch). Delete them again just-in-time and
            // retry once so the regeneration race cannot block the checkout.
            context.log.warn("[retry] untracked collision, discarding approved files - ${directory.path}")
            discardApprovedFiles(
                directory,
                context.approvedCollisionDiscards[target.path].orEmpty(),
                context.log,
                OperationStage.CHECKOUT,
                target.path,
            )
            return executeOnce(context, target, directory, state)
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
                discardThenCheckout(context, target, directory) {
                    context.git.checkoutExisting(directory, target.branch)
                }

            context.git.remoteBranchExists(directory, target.branch) -> {
                context.log.info("local branch missing, creating from origin/${target.branch}")
                discardThenCheckout(context, target, directory) {
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

        context.log.info("checkout ok")
        return Result(
            state = state.withSuccessfulCheckout(target.path),
            succeeded = true,
        )
    }

    /**
     * Deletes the approved collision files for [target] immediately before its checkout
     * write. The up-front discard step only handles dirty+Stash repositories (where
     * `git stash push -u` would sweep the files into the WIP backup); every other
     * approved repository — Force, or clean enough that it is never stashed — is deleted
     * here, right before the write, so a repository that is skipped downstream (a failed
     * main checkout disabling submodules, a topology change unregistering the path)
     * never loses approved files without its checkout actually running. (A dirty+Stash
     * repo is still deleted up-front by design, and a later skip loses those files — the
     * accepted cost of keeping them out of the `-u` stash.) A delete failure is logged
     * and left to the checkout to surface: if the file still blocks, the write fails
     * with the structured collision error.
     */
    private fun <T> discardThenCheckout(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        action: () -> T,
    ): T {
        discardApprovedFiles(
            directory,
            context.approvedCollisionDiscards[target.path].orEmpty(),
            context.log,
            OperationStage.CHECKOUT,
            target.path,
        ).forEach { issue ->
            context.log.warn("[discard] ${target.path}: ${issue.diagnostic}")
        }
        return action()
    }

    /** True when the checkout failed on untracked-file overwrite AND this repo has approved discards. */
    private fun shouldRetryAfterDiscard(context: SwitchContext, target: RepoTarget, result: Result): Boolean {
        if (context.approvedCollisionDiscards[target.path].orEmpty().isEmpty()) return false
        return result.issues.any {
            it.code == OperationIssueCode.CHECKOUT_FAILED &&
                it.diagnostic.orEmpty().contains(UNTRACKED_OVERWRITE_HINT)
        }
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
