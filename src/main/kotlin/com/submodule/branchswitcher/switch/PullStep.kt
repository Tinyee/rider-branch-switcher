package com.submodule.branchswitcher.switch

import java.io.File

/** If global [SwitchOptions.pull] and per-preset [Preset.pullEnabled] are both enabled, pull --ff-only for each target. */
class PullStep : SwitchStep {
    override val name = "pull"

    override fun execute(context: SwitchContext): StepResult {
        if (!context.options.pull || !context.preset.pullEnabled) return StepResult.Success

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targets()) {
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !isGitRepo(dir)) continue
            val p = context.git.pullFf(dir, target.branch)
            if (p.ok) {
                context.log("pull ok — ${target.path}")
            } else {
                context.log("[warn] pull failed (kept local): ${p.stderr.lines().firstOrNull() ?: ""}")
                failures[target.path] = "pull had warnings"
            }
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
