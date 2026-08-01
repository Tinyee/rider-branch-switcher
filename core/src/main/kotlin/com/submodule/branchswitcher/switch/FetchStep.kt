package com.submodule.branchswitcher.switch

/** If fetchFirst is enabled, fetch --prune for each target. Non-fatal on failure. */
class FetchStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("fetch", scope)

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.fetchFirst) return StepExecution(StepResult.Success, state)

        val failures = LinkedHashMap<String, String>()
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
                failures[target.path] = "fetch had warnings"
            }
        }
        val result = if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
        return StepExecution(result, state)
    }
}
