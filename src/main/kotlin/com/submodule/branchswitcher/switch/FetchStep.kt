package com.submodule.branchswitcher.switch

import java.io.File

/** If fetchFirst is enabled, fetch --prune for each target. Non-fatal on failure. */
class FetchStep : SwitchStep {
    override val name = "fetch"

    override fun execute(context: SwitchContext): StepResult {
        if (!context.options.fetchFirst) return StepResult.Success

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targets()) {
            context.indicator?.checkCanceled()
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !isGitRepo(dir)) continue
            // Skip if already on target (no need to fetch latest)
            val cur = context.git.currentBranch(dir)
            if (cur == target.branch) continue

            val f = context.git.fetch(dir)
            if (!f.ok) {
                context.log("fetch warn: ${f.stderr} (${target.path})")
                failures[target.path] = "fetch had warnings"
            }
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
