package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.DirtyAction
import java.io.File

/**
 * For each target with a dirty working tree, apply the configured strategy (stash / skip / force).
 */
class DirtyHandlingStep : SwitchStep {
    override val name = "dirty handling"

    override fun execute(context: SwitchContext): StepResult {
        val failures = LinkedHashMap<String, String>()
        val targets = context.preset.targets()
        val total = targets.size
        for ((idx, target) in targets.withIndex()) {
            context.indicator?.apply {
                fraction = idx.toDouble() / total
                text2 = if (target.path == ".") context.projectRoot.fileName.toString() else target.path
                checkCanceled()
            }
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists()) continue
            if (!isGitRepo(dir)) continue

            if (context.git.isDirty(dir)) {
                when (context.options.dirty) {
                    DirtyAction.Skip -> {
                        context.log("[skip] working tree dirty — ${target.path}")
                        failures[target.path] = "working tree dirty"
                        context.skippedPaths.add(target.path)
                        continue
                    }
                    DirtyAction.Stash -> {
                        val cur = context.git.currentBranch(dir)
                        if (cur != null && cur == target.branch) {
                            context.log("already on '${target.branch}', no stash needed")
                        } else {
                            val r = context.git.stash(dir, "branch-switcher: before -> ${target.branch}")
                            context.log("stash: ${if (r.ok) "ok" else "FAIL"} (${target.path})")
                            if (r.stderr.isNotBlank()) context.log(r.stderr)
                            if (!r.ok) {
                                failures[target.path] = "stash failed"
                                context.skippedPaths.add(target.path)
                                continue
                            }
                            context.stashedPaths[target.path] = "before -> ${target.branch}"
                        }
                    }
                    DirtyAction.Force -> context.log("[force] proceeding with dirty tree — ${target.path}")
                }
            }
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
