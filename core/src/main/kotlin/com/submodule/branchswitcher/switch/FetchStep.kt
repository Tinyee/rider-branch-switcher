package com.submodule.branchswitcher.switch

/** If fetchFirst is enabled, fetch --prune for each target. Non-fatal on failure. */
class FetchStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("fetch", scope)
    override val stage = OperationStage.FETCH

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.fetchFirst) return StepExecution(StepResult.Success, state)

        val issues = mutableListOf<OperationIssue>()
        for (target in context.preset.targetsFor(scope)) {
            context.cancellationHandle?.checkCanceled()
            if (state.isSkipped(target.path)) {
                context.log.info("[skip] fetch - target disabled for ${target.path}")
                continue
            }
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !context.git.isGitRepo(dir)) continue

            val f = context.git.fetch(dir)
            if (!f.ok) {
                context.log.warn("fetch warn: ${f.diagnostic()} (${target.path})")
                issues += OperationIssue(
                    stage = stage,
                    code = OperationIssueCode.FETCH_FAILED,
                    repositoryPath = target.path,
                    diagnostic = f.diagnostic(),
                )
            }
        }
        val result = issues.toStepResult()
        return StepExecution(result, state)
    }
}
