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
        var registeredPaths = loadRegisteredSubmodulePaths(context, mainCheckoutSucceeded)
        val targets = context.preset.targetsFor(scope)

        try {
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()

                val isMain = target.path == "."
                val directory = resolveGitDir(context.projectRoot, target.path)
                val label = if (isMain) context.projectRoot.fileName.toString() else target.path

                if (nextState.isSkipped(target.path)) {
                    context.log.info("[skip] $label - target disabled by an earlier step")
                    continue
                }

                context.log.info("")
                context.log.info("--- $label - ${target.branch} ---")

                if (!isMain && mainCheckoutSucceeded && registeredPaths?.contains(target.path) == false) {
                    context.log.warn(
                        "[skip] $label - not registered in target .gitmodules; " +
                            "obsolete worktree retained",
                    )
                    failures[target.path] = "not registered in target .gitmodules"
                    nextState = nextState.withSkipped(target.path)
                    continue
                }

                val preparation = SubmoduleInitializer.prepare(
                    context = context,
                    target = target,
                    directory = directory,
                    mainCheckoutSucceeded = mainCheckoutSucceeded,
                )
                if (preparation.initializedBySwitch) {
                    nextState = nextState.withInitializedSubmodule(target.path)
                }
                val preparationFailure = preparation.failure
                if (preparationFailure != null) {
                    failures[target.path] = preparationFailure
                }
                if (!preparation.ready) {
                    continue
                }

                if (preparation.initializedBySwitch && !directory.exists()) {
                    context.log.info("[skip] dir not found after init: ${directory.absolutePath}")
                    failures[target.path] = "dir not found after init"
                    continue
                }
                if (preparation.initializedBySwitch && !context.git.isGitRepo(directory)) {
                    context.log.info("[skip] not a git repo after init")
                    failures[target.path] = "not a git repo after init"
                    continue
                }

                // The earlier fetch step skipped a repository that did not yet
                // exist. Record initialization first so fetch cancellation cannot
                // hide the retained worktree from recovery and notifications.
                if (preparation.initializedBySwitch && context.options.fetchFirst) {
                    val fetchResult = context.git.fetch(directory)
                    if (!fetchResult.ok) {
                        context.log.warn(
                            "fetch after init warn: ${fetchResult.diagnostic()} (${target.path})",
                        )
                        failures[target.path] = "fetch after init had warnings"
                    }
                }

                val checkout = BranchCheckout.execute(context, target, directory, nextState)
                nextState = checkout.state
                val checkoutFailure = checkout.failure
                if (checkoutFailure != null) {
                    failures[target.path] = checkoutFailure
                }
                if (checkout.succeeded) {
                    if (isMain) mainCheckoutSucceeded = true
                    registeredPaths = loadRegisteredSubmodulePaths(context, mainCheckoutSucceeded)
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

    private fun loadRegisteredSubmodulePaths(
        context: SwitchContext,
        mainCheckoutSucceeded: Boolean,
    ): Set<String>? = if (mainCheckoutSucceeded) {
        context.git.registeredSubmodulePaths(context.projectRoot.toFile())
    } else {
        null
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
