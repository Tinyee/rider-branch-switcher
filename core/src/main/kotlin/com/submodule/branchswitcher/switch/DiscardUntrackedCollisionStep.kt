package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.RepoTarget

/**
 * Isolates the main repository's approved untracked collision files into a path-scoped
 * stash before the switch's checkout attempts them, so git does not refuse to overwrite
 * them, and nothing is permanently deleted until the repo provably switched to the target.
 *
 * Runs after [FetchStep] and before [DirtyHandlingStep]: the fetch refreshes the target ref,
 * the isolation precedes the main `git stash push -u` so the approved files are never swept
 * into the WIP backup (which would otherwise re-apply them onto the freshly checked-out
 * tracked versions during restore), and the approved stash is recorded first so recovery
 * restores it in reverse creation order (WIP before approved).
 *
 * Submodule isolation is handled inside [SubmoduleTreeStep], after the topology write gate
 * confirms each path is registered — never here, where the new topology is not yet known.
 */
class DiscardUntrackedCollisionStep : SwitchStep {
    override val name = "discard untracked collisions"
    override val stage = OperationStage.DIRTY_HANDLING

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val approved = context.approvedCollisionDiscards
        if (approved.isEmpty()) return StepExecution(StepResult.Success, state)
        // Untracked collision files count toward the dirty count, so under the Skip
        // strategy every repo with approved discards is skipped before checkout: isolating
        // them would be unrecoverable loss with no switch happening.
        if (context.options.dirty == DirtyAction.Skip) return StepExecution(StepResult.Success, state)

        val mainApproved = approved["."].orEmpty()
        if (mainApproved.isEmpty()) return StepExecution(StepResult.Success, state)

        context.operationControl?.checkCancelled()
        val target = RepoTarget(".", context.preset.main)
        // The repo is already on the target branch (the branch may have changed since the
        // user approved the discard): checkout will not run, so isolating approved files
        // would be needless data loss.
        if (context.checkpoint["."]?.branch == target.branch) {
            return StepExecution(StepResult.Success, state)
        }
        val dir = resolveGitDir(context.projectRoot, ".")
        if (!dir.exists()) return StepExecution(StepResult.Success, state)

        val outcome = stashApprovedCollisions(context, target, dir, state, OperationStage.DIRTY_HANDLING)
        val stepIssues = when (outcome) {
            is ApprovedStashOutcome.Blocked -> listOf(outcome.issue)
            is ApprovedStashOutcome.Proceed -> emptyList()
        }
        return StepExecution(stepIssues.toStepResult(), outcome.state)
    }
}
