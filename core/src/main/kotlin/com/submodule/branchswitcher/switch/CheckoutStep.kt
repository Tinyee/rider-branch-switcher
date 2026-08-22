package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget

/** Checks out the main repository after its optional fetch. */
class CheckoutStep : SwitchStep {
    override val name = "checkout main"
    override val stage = OperationStage.CHECKOUT

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val target = RepoTarget(".", context.preset.main)
        if (state.isSkipped(target.path)) {
            context.log.info("[skip] main repository - target disabled by an earlier step")
            return StepExecution(StepResult.Success, state)
        }

        context.progressHandle?.updateProgress(0, 1, context.projectRoot, ".")
        context.operationControl?.checkCancelled()
        context.log.info("")
        context.log.info("--- ${context.projectRoot.fileName} - ${target.branch} ---")

        val directory = context.projectRoot.toFile()
        if (!directory.exists() || !context.git.isGitRepo(directory)) {
            context.log.warn("[fail] main repository is unavailable - ${directory.path}")
            return StepExecution(
                StepResult.Partial(
                    listOf(
                        OperationIssue(
                            stage = stage,
                            code = OperationIssueCode.MAIN_REPOSITORY_UNAVAILABLE,
                            repositoryPath = target.path,
                        ),
                    ),
                ),
                state,
            )
        }

        val checkout = BranchCheckout.execute(context, target, directory, state)
        val result = checkout.issues.toStepResult()
        return StepExecution(result, checkout.state)
    }
}
