package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget

/** Checks out the main repository after its optional fetch. */
class CheckoutStep : SwitchStep {
    override val name = "checkout main"

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val target = RepoTarget(".", context.preset.main)
        if (state.isSkipped(target.path)) {
            context.log.info("[skip] main repository - target disabled by an earlier step")
            return StepExecution(StepResult.Success, state)
        }

        context.progressHandle?.apply {
            fraction = 0.0
            text2 = context.projectRoot.fileName.toString()
        }
        context.cancellationHandle?.checkCanceled()
        context.log.info("")
        context.log.info("--- ${context.projectRoot.fileName} - ${target.branch} ---")

        val directory = context.projectRoot.toFile()
        if (!directory.exists() || !context.git.isGitRepo(directory)) {
            context.log.warn("[fail] main repository is unavailable")
            return StepExecution(
                StepResult.Partial(mapOf(target.path to "main repository unavailable")),
                state,
            )
        }

        val checkout = BranchCheckout.execute(context, target, directory, state)
        val result = checkout.failure?.let { failure ->
            StepResult.Partial(mapOf(target.path to failure))
        } ?: StepResult.Success
        return StepExecution(result, checkout.state)
    }
}
