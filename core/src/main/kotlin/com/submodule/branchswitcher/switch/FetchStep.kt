package com.submodule.branchswitcher.switch

/** If fetchFirst is enabled, fetch --prune for each target. Non-fatal on failure. */
class FetchStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("fetch", scope)

    override fun execute(context: SwitchContext): StepResult {
        if (!context.options.fetchFirst) return StepResult.Success

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targetsFor(scope)) {
            context.cancellationHandle?.checkCanceled()
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !context.git.isGitRepo(dir)) continue

            val f = context.git.fetch(dir)
            if (!f.ok) {
                context.log.warn("fetch warn: ${f.stderr} (${target.path})")
                failures[target.path] = "fetch had warnings"
            }
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
