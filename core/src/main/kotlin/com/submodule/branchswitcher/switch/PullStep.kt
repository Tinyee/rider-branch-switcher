package com.submodule.branchswitcher.switch

/** If resolved [SwitchOptions.pull] is enabled, pull --ff-only for each target. */
class PullStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("pull", scope)

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.pull) {
            val restore = restoreTrackedStashes(context, state)
            val result = if (restore.failures.isEmpty()) {
                StepResult.Success
            } else {
                StepResult.Partial(restore.failures)
            }
            return StepExecution(result, restore.state)
        }

        val failures = LinkedHashMap<String, String>()
        for (target in context.preset.targetsFor(scope)) {
            val dir = resolveGitDir(context.projectRoot, target.path)
            if (!dir.exists() || !context.git.isGitRepo(dir)) continue
            // Only pull on repos where checkout actually succeeded
            if (!state.checkoutSucceeded(target.path)) {
                context.log.info("[skip] pull - checkout did not succeed for ${target.path}")
                continue
            }
            val cur = context.git.currentBranch(dir)
            if (cur != target.branch) {
                context.log.info("[skip] pull - current branch is '${cur ?: "(detached)"}', expected '${target.branch}'")
                continue
            }
            val p = context.git.pullFf(dir, target.branch)
            if (p.ok) {
                context.log.info("pull ok - ${target.path}")
            } else {
                context.log.warn(" pull failed (kept local): ${p.diagnostic()}")
                failures[target.path] = "pull had warnings"
            }
        }
        val restore = restoreTrackedStashes(context, state)
        failures.putAll(restore.failures)
        val result = if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
        return StepExecution(result, restore.state)
    }

    /** Pop stashes that were created during DirtyHandlingStep, now that checkout + pull are done. */
    private fun restoreTrackedStashes(context: SwitchContext, state: SwitchState): StashRestoreResult {
        val selectedPaths = context.preset.targetsFor(scope).mapTo(hashSetOf()) { it.path }
        return restoreTrackedStashes(
            context.projectRoot, context.git, context.log, state, selectedPaths,
        )
    }
}

data class StashRestoreResult(
    val state: SwitchState,
    val failures: Map<String, String>,
)

/** Restores tracked stashes and retains failed entries so a later recovery can retry them. */
internal fun restoreTrackedStashes(
    projectRoot: java.nio.file.Path,
    git: com.submodule.branchswitcher.git.SwitchGitClient,
    log: com.submodule.branchswitcher.log.AppLogger,
    state: SwitchState,
    selectedPaths: Set<String>? = null,
): StashRestoreResult {
    val failures = linkedMapOf<String, String>()
    var nextState = state
    for ((path, msg) in state.stashesSnapshot()) {
        if (selectedPaths != null && path !in selectedPaths) continue
        val dir = resolveGitDir(projectRoot, path)
        if (!dir.exists() || !git.isGitRepo(dir)) {
            log.warn("[fail] stash pop skipped - repository unavailable for $path ($msg)")
            failures[path] = "stash repository unavailable"
            continue
        }
        val popResult = git.stashPop(dir)
        if (popResult.ok) {
            log.info("stash pop ok ($msg)")
            nextState = nextState.withoutStash(path)
        } else {
            log.warn("[fail] stash pop failed for $path: ${popResult.diagnostic()}")
            failures[path] = "stash pop failed"
        }
    }
    return StashRestoreResult(nextState, failures)
}
