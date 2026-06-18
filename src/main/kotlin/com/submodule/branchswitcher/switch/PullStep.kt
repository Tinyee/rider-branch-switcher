package com.submodule.branchswitcher.switch

import java.io.File

/** If resolved [SwitchOptions.pull] is enabled, pull --ff-only for each target. */
class PullStep : SwitchStep {
    override val name = "pull"

    override fun execute(context: SwitchContext): StepResult {
        if (!context.options.pull) {
            // Still pop stashes even when pull is disabled
            popStashes(context)
            return StepResult.Success
        }

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targets()) {
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !context.git.isGitRepo(dir)) continue
            // Only pull on repos where checkout actually succeeded
            if (target.path !in context.successfulCheckouts) {
                context.log.info("[skip] pull — checkout did not succeed for ${target.path}")
                continue
            }
            val cur = context.git.currentBranch(dir)
            if (cur != target.branch) {
                context.log.info("[skip] pull — current branch is '${cur ?: "(detached)"}', expected '${target.branch}'")
                continue
            }
            val p = context.git.pullFf(dir, target.branch)
            if (p.ok) {
                context.log.info("pull ok — ${target.path}")
            } else {
                context.log.warn(" pull failed (kept local): ${p.stderr.lines().firstOrNull() ?: ""}")
                failures[target.path] = "pull had warnings"
            }
        }
        popStashes(context)
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }

    /** Pop stashes that were created during DirtyHandlingStep, now that checkout + pull are done. */
    private fun popStashes(context: SwitchContext) {
        for ((path, msg) in context.stashedPaths.toMap()) {
            val dir = resolveGitDir(context.projectRoot, path)
            if (!dir.exists() || !context.git.isGitRepo(dir)) {
                context.log.warn(" stash pop skipped — dir gone for $path ($msg)")
                context.stashedPaths.remove(path)
                continue
            }
            val popResult = context.git.stashPop(dir)
            if (popResult.ok) {
                context.log.info("stash pop ok ($msg)")
                context.stashedPaths.remove(path)
            } else {
                context.log.warn("[fail] stash pop failed for $path: ${popResult.stderr.lines().firstOrNull() ?: ""}")
            }
        }
    }
}
