package com.submodule.branchswitcher.switch

/**
 * For the main repository with a dirty working tree, apply the configured strategy
 * (stash, skip, or attempt checkout without stashing).
 *
 * Submodule dirty handling no longer happens here: it runs inside
 * [SubmoduleTreeStep.updatePreparedTarget], after the topology write gate confirms the
 * path is registered, so an obsolete/unregistered worktree is never stashed or deleted.
 */
class DirtyHandlingStep : SwitchStep {
    override val name = "dirty handling"
    override val stage = OperationStage.DIRTY_HANDLING

    @Suppress("TooGenericExceptionCaught") // preserve state for cancellation and Git query failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val issues = mutableListOf<OperationIssue>()
        var nextState = state
        try {
            val targets = context.preset.targetsFor(SwitchTargetScope.MAIN)
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()
                val repositoryDirectory = resolveGitDir(context.projectRoot, target.path)
                val facts = inspectDirtyState(context, repositoryDirectory) ?: continue
                val outcome = handleTargetDirtyState(context, target, repositoryDirectory, facts, nextState, issues)
                nextState = outcome.state
            }
        } catch (e: RuntimeException) {
            throw SwitchStepException(nextState, e)
        }
        val result = issues.toStepResult()
        return StepExecution(result, nextState)
    }

    private fun updateProgress(context: SwitchContext, index: Int, total: Int, path: String) {
        context.progressHandle?.updateProgress(index, total, context.projectRoot, path)
    }
}
