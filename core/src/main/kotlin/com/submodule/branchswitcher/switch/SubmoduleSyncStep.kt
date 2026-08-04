package com.submodule.branchswitcher.switch

/** After main repo checkout, run `git submodule sync --recursive` to align .gitmodules URLs. */
class SubmoduleSyncStep : SwitchStep {
    override val name = "submodule sync"
    override val stage = OperationStage.SUBMODULE_SYNC

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        // Only sync if main checkout succeeded - otherwise .gitmodules may reflect old branch
        if (!state.checkoutSucceeded(".")) {
            context.log.info("[skip] submodule sync - main checkout did not succeed")
            return StepExecution(
                StepResult.Partial(
                    listOf(
                        OperationIssue(
                            stage = stage,
                            code = OperationIssueCode.MAIN_CHECKOUT_REQUIRED,
                            repositoryPath = ".",
                        ),
                    ),
                ),
                state.withSubmodulesDisabled(context),
            )
        }
        val dir = context.projectRoot.toFile()
        val s = context.git.submoduleSync(dir)
        if (s.ok) {
            context.log.info("submodule sync ok")
            return StepExecution(StepResult.Success, state)
        } else {
            context.log.warn(" submodule sync failed: ${s.diagnostic()}")
            return StepExecution(
                StepResult.Partial(
                    listOf(
                        OperationIssue(
                            stage = stage,
                            code = OperationIssueCode.SUBMODULE_SYNC_FAILED,
                            repositoryPath = ".",
                            diagnostic = s.diagnostic(),
                        ),
                    ),
                ),
                state.withSubmodulesDisabled(context),
            )
        }
    }

    private fun SwitchState.withSubmodulesDisabled(context: SwitchContext): SwitchState =
        context.preset.targetsFor(SwitchTargetScope.SUBMODULES).fold(this) { current, target ->
            current.withSkipped(target.path)
        }
}
