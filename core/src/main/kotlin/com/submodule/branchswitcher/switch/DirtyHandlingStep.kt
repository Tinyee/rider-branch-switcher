package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.DirtyAction

/**
 * For each target with a dirty working tree, apply the configured strategy (stash / skip / force).
 */
class DirtyHandlingStep : SwitchStep {
    override val name = "dirty handling"

    @Suppress("TooGenericExceptionCaught") // preserve state for cancellation and Git query failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val failures = LinkedHashMap<String, String>()
        var nextState = state
        try {
            val targets = context.preset.targets()
            val total = targets.size
            for ((index, target) in targets.withIndex()) {
                context.progressHandle?.apply {
                    fraction = index.toDouble() / total
                    text2 = if (target.path == ".") context.projectRoot.fileName.toString() else target.path
                }
                context.cancellationHandle?.checkCanceled()
                val repositoryDirectory = resolveGitDir(context.projectRoot, target.path)
                if (!repositoryDirectory.exists()) continue
                if (!context.git.isGitRepo(repositoryDirectory)) continue

                if (context.git.isDirty(repositoryDirectory)) {
                    when (context.options.dirty) {
                        DirtyAction.Skip -> {
                            context.log.info("[skip] working tree dirty - ${target.path}")
                            failures[target.path] = "working tree dirty"
                            nextState = nextState.withSkipped(target.path)
                            continue
                        }
                        DirtyAction.Stash -> {
                            val currentBranch = context.git.currentBranch(repositoryDirectory)
                            if (currentBranch != null && currentBranch == target.branch) {
                                context.log.info("already on '${target.branch}', no stash needed")
                            } else {
                                val stashResult = context.git.stash(
                                    repositoryDirectory,
                                    "branch-switcher: before -> ${target.branch}",
                                )
                                if (stashResult.ok) {
                                    context.log.info("stash: ok (${target.path})")
                                } else {
                                    context.log.warn(
                                        "stash: FAIL (${target.path}): ${stashResult.diagnostic()}",
                                    )
                                }
                                if (!stashResult.ok) {
                                    failures[target.path] = "stash failed"
                                    nextState = nextState.withSkipped(target.path)
                                    continue
                                }
                                nextState = nextState.withTrackedStash(
                                    target.path,
                                    "before -> ${target.branch}",
                                )
                            }
                        }
                        DirtyAction.Force -> context.log.info("[force] proceeding with dirty tree - ${target.path}")
                    }
                }
            }
        } catch (e: RuntimeException) {
            throw SwitchStepException(nextState, e)
        }
        val result = if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
        return StepExecution(result, nextState)
    }
}
