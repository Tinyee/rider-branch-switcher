package com.submodule.branchswitcher.switch

/**
 * Coordinates repository preparation and branch checkout for the selected
 * targets. The detailed Git decisions live in focused helpers.
 */
class CheckoutStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("checkout", scope)

    @Suppress("TooGenericExceptionCaught") // preserve the latest state across Git query failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val failures = LinkedHashMap<String, String>()
        var nextState = state
        var mainCheckoutSucceeded = state.checkoutSucceeded(".")
        val targets = context.preset.targetsFor(scope)

        try {
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()

                val isMain = target.path == "."
                val directory = resolveGitDir(context.projectRoot, target.path)
                val label = if (isMain) context.projectRoot.fileName.toString() else target.path

                if (nextState.isSkipped(target.path)) {
                    context.log.info("[skip] $label - skipped by dirty handling")
                    continue
                }

                context.log.info("")
                context.log.info("--- $label - ${target.branch} ---")

                val preparation = SubmoduleInitializer.prepare(
                    context = context,
                    target = target,
                    directory = directory,
                    mainCheckoutSucceeded = mainCheckoutSucceeded,
                )
                val preparationFailure = preparation.failure
                if (preparationFailure != null) {
                    failures[target.path] = preparationFailure
                }
                if (!preparation.ready) {
                    continue
                }

                val checkout = BranchCheckout.execute(context, target, directory, nextState)
                nextState = checkout.state
                val checkoutFailure = checkout.failure
                if (checkoutFailure != null) {
                    failures[target.path] = checkoutFailure
                }
                if (isMain && checkout.succeeded) {
                    mainCheckoutSucceeded = true
                }
            }
        } catch (error: RuntimeException) {
            throw SwitchStepException(nextState, error)
        }

        val result = if (failures.isEmpty()) {
            StepResult.Success
        } else {
            StepResult.Partial(failures)
        }
        return StepExecution(result, nextState)
    }

    private fun updateProgress(
        context: SwitchContext,
        index: Int,
        total: Int,
        path: String,
    ) {
        val progress = context.progressHandle ?: return
        progress.fraction = index.toDouble() / total
        progress.text2 = if (path == ".") context.projectRoot.fileName.toString() else path
    }
}
