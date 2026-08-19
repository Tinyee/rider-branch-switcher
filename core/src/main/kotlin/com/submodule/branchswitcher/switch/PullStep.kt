package com.submodule.branchswitcher.switch

/** If resolved [SwitchOptions.pull] is enabled, pull --ff-only for each target. */
class PullStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("pull", scope)
    override val stage = OperationStage.PULL

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.pull) {
            // Stash restoration is deferred to the end of the whole pipeline
            // (SwitchExecutor.runSteps), so a later failure cannot dirty a tree
            // before the rollback's clean-tree requirement runs.
            return StepExecution(StepResult.Success, state)
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
                context.log.warn(" pull failed (kept local) - ${repositoryDirectory.path}: ${pullResult.diagnostic()}")
                issues += OperationIssue(
                    stage = stage,
                    code = OperationIssueCode.PULL_FAILED,
                    repositoryPath = target.path,
                    diagnostic = pullResult.diagnostic(),
                )
            }
        }
        val result = if (issues.isEmpty()) StepResult.Success else StepResult.Partial(issues)
        return StepExecution(result, state)
    }
}
