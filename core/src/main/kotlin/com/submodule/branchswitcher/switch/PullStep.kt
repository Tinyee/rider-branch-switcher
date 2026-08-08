package com.submodule.branchswitcher.switch

/** If resolved [SwitchOptions.pull] is enabled, pull --ff-only for each target. */
class PullStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("pull", scope)
    override val stage = OperationStage.PULL

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.pull) {
            val restore = restoreTrackedStashes(context, state)
            val result = if (restore.issues.isEmpty()) {
                StepResult.Success
            } else {
                StepResult.Partial(restore.issues)
            }
            return StepExecution(result, restore.state)
        }

        val issues = mutableListOf<OperationIssue>()
        for (target in context.preset.targetsFor(scope)) {
            val repositoryDirectory = resolveGitDir(context.projectRoot, target.path)
            if (!repositoryDirectory.exists() || !context.git.isGitRepo(repositoryDirectory)) continue
            // Only pull on repos where checkout actually succeeded
            if (!state.checkoutSucceeded(target.path)) {
                context.log.info("[skip] pull - checkout did not succeed for ${target.path}")
                continue
            }
            val currentBranch = context.git.currentBranch(repositoryDirectory)
            if (currentBranch != target.branch) {
                context.log.info(
                    "[skip] pull - current branch is '${currentBranch ?: "(detached)"}', " +
                        "expected '${target.branch}'",
                )
                continue
            }
            val pullResult = context.git.pullFf(repositoryDirectory, target.branch)
            if (pullResult.ok) {
                context.log.info("pull ok - ${target.path}")
            } else {
                context.log.warn(" pull failed (kept local): ${pullResult.diagnostic()}")
                issues += OperationIssue(
                    stage = stage,
                    code = OperationIssueCode.PULL_FAILED,
                    repositoryPath = target.path,
                    diagnostic = pullResult.diagnostic(),
                )
            }
        }
        val restore = restoreTrackedStashes(context, state)
        issues += restore.issues
        val result = if (issues.isEmpty()) StepResult.Success else StepResult.Partial(issues)
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
    val issues: List<OperationIssue>,
)

/** Restores tracked stashes and retains failed entries so a later recovery can retry them. */
@Suppress("TooGenericExceptionCaught") // preserve successfully restored entries if a later Git query fails
internal fun restoreTrackedStashes(
    projectRoot: java.nio.file.Path,
    git: com.submodule.branchswitcher.git.SwitchGitClient,
    log: com.submodule.branchswitcher.log.AppLogger,
    state: SwitchState,
    selectedPaths: Set<String>? = null,
): StashRestoreResult {
    val issues = mutableListOf<OperationIssue>()
    var nextState = state
    try {
        for ((path, stash) in state.stashesSnapshot()) {
            if (selectedPaths != null && path !in selectedPaths) continue
            val repositoryDirectory = resolveGitDir(projectRoot, path)
            if (stash.oid == null) {
                log.error("[fail] stash restore skipped - identity unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                    repositoryPath = path,
                    severity = OperationIssueSeverity.ERROR,
                )
                continue
            }
            if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
                log.warn("[fail] stash apply skipped - repository unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_REPOSITORY_UNAVAILABLE,
                    repositoryPath = path,
                )
                continue
            }
            val applyResult = git.stashApply(repositoryDirectory, stash.oid)
            if (applyResult.ok) {
                log.info("stash apply ok; recovery backup retained (${stash.message}, oid=${stash.oid})")
                nextState = nextState.withRestoredStashBackup(path)
            } else {
                log.warn("[fail] stash apply failed for $path: ${applyResult.diagnostic()}")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_RESTORE_FAILED,
                    repositoryPath = path,
                    diagnostic = applyResult.diagnostic(),
                )
            }
        }
    } catch (e: RuntimeException) {
        throw SwitchStepException(nextState, e)
    }
    return StashRestoreResult(nextState, issues)
}
