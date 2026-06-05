package com.submodule.branchswitcher.switch

import java.io.File

/** If options.pull and preset.pull are both enabled, pull --ff-only for each target. */
class PullStep : SwitchStep {
    override val name = "pull"

    override fun execute(context: SwitchContext): StepResult {
        if (!context.options.pull || !context.preset.pull) return StepResult.Success

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targets()) {
            val dir = resolveDir(context, target.path)
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

    private fun resolveDir(context: SwitchContext, path: String): File =
        if (path == ".") context.projectRoot.toFile()
        else context.projectRoot.resolve(path).toFile()

    private fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
