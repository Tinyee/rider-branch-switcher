package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget

/**
 * Updates submodules in parent-first order.
 *
 * Each parent is fully prepared, fetched, checked out, and pulled before its
 * descendants are inspected. This keeps nested `.gitmodules` data current and
 * prevents child repositories from being updated against stale parent state.
 */
class SubmoduleTreeStep : SwitchStep {
    override val name = "switch submodules"

    @Suppress("TooGenericExceptionCaught") // preserve completed per-repository state across Git failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val targets = context.preset.targetsFor(SwitchTargetScope.SUBMODULES)
        if (targets.isEmpty()) return StepExecution(StepResult.Success, state)

        val failures = linkedMapOf<String, String>()
        var nextState = state
        var registeredPaths = loadRegisteredPaths(context, nextState)

        try {
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()

                if (nextState.isSkipped(target.path)) {
                    context.log.info("[skip] ${target.path} - target disabled by an earlier step")
                    nextState = restoreTargetStash(context, nextState, target.path, failures)
                    continue
                }

                if (!nextState.checkoutSucceeded(".")) {
                    failures[target.path] = "main checkout did not succeed"
                    nextState = disableTargetAndDescendants(nextState, targets, target.path)
                    nextState = restoreTargetStash(context, nextState, target.path, failures)
                    continue
                }

                if (submoduleRegistrationStatus(target.path, registeredPaths) ==
                    SubmoduleRegistrationStatus.UNREGISTERED
                ) {
                    context.log.warn(
                        "[skip] ${target.path} - not registered in current .gitmodules graph; " +
                            "obsolete worktree retained",
                    )
                    failures[target.path] = "not registered in current .gitmodules graph"
                    nextState = disableTargetAndDescendants(nextState, targets, target.path)
                    nextState = restoreTargetStash(context, nextState, target.path, failures)
                    continue
                }

                context.log.info("")
                context.log.info("--- ${target.path} - ${target.branch} ---")
                val directory = resolveGitDir(context.projectRoot, target.path)
                val registration = registrationLocation(context, targets, target)
                val preparation = SubmoduleInitializer.prepare(
                    context = context,
                    target = target,
                    directory = directory,
                    registrationRoot = registration.root,
                    registrationPath = registration.path,
                )
                if (preparation.initializedBySwitch) {
                    nextState = nextState.withInitializedSubmodule(target.path)
                }
                preparation.failure?.let { failures[target.path] = it }
                if (!preparation.ready) {
                    nextState = disableDescendants(nextState, targets, target.path)
                    nextState = restoreTargetStash(context, nextState, target.path, failures)
                    continue
                }

                if (context.options.fetchFirst) {
                    val fetch = context.git.fetch(directory)
                    if (!fetch.ok) {
                        context.log.warn("fetch warn: ${fetch.diagnostic()} (${target.path})")
                        failures[target.path] = "fetch had warnings"
                    }
                }

                val checkout = BranchCheckout.execute(context, target, directory, nextState)
                nextState = checkout.state
                checkout.failure?.let { failures[target.path] = it }
                if (!checkout.succeeded) {
                    nextState = disableDescendants(nextState, targets, target.path)
                    nextState = restoreTargetStash(context, nextState, target.path, failures)
                    continue
                }

                if (context.options.pull) {
                    val pull = context.git.pullFf(directory, target.branch)
                    if (!pull.ok) {
                        context.log.warn("pull failed (kept local): ${pull.diagnostic()}")
                        failures[target.path] = "pull had warnings"
                    } else {
                        context.log.info("pull ok - ${target.path}")
                    }
                }

                if (hasDescendants(targets, target.path)) {
                    val sync = context.git.submoduleSync(directory)
                    if (!sync.ok) {
                        context.log.warn("nested submodule sync failed: ${sync.diagnostic()} (${target.path})")
                        failures[target.path] = "nested submodule sync failed"
                        nextState = disableDescendants(nextState, targets, target.path)
                    }
                    registeredPaths = loadRegisteredPaths(context, nextState)
                }
                nextState = restoreTargetStash(context, nextState, target.path, failures)
            }
        } catch (error: RuntimeException) {
            throw SwitchStepException(nextState, error)
        }

        val result = if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
        return StepExecution(result, nextState)
    }

    private fun loadRegisteredPaths(context: SwitchContext, state: SwitchState): Set<String>? =
        if (state.checkoutSucceeded(".")) {
            context.git.registeredSubmodulePaths(context.projectRoot.toFile())
        } else {
            null
        }

    private fun restoreTargetStash(
        context: SwitchContext,
        state: SwitchState,
        path: String,
        failures: MutableMap<String, String>,
    ): SwitchState {
        val restore = restoreTrackedStashes(
            context.projectRoot,
            context.git,
            context.log,
            state,
            setOf(path),
        )
        restore.failures.forEach { (failedPath, failure) -> failures[failedPath] = failure }
        return restore.state
    }

    private fun disableTargetAndDescendants(
        state: SwitchState,
        targets: List<RepoTarget>,
        path: String,
    ): SwitchState = targets
        .asSequence()
        .map(RepoTarget::path)
        .filter { it == path || it.startsWith("$path/") }
        .fold(state, SwitchState::withSkipped)

    private fun disableDescendants(
        state: SwitchState,
        targets: List<RepoTarget>,
        path: String,
    ): SwitchState = targets
        .asSequence()
        .map(RepoTarget::path)
        .filter { it.startsWith("$path/") }
        .fold(state, SwitchState::withSkipped)

    private fun hasDescendants(targets: List<RepoTarget>, path: String): Boolean =
        targets.any { it.path.startsWith("$path/") }

    private fun registrationLocation(
        context: SwitchContext,
        targets: List<RepoTarget>,
        target: RepoTarget,
    ): RegistrationLocation {
        val parent = targets
            .asSequence()
            .filter { candidate -> target.path.startsWith("${candidate.path}/") }
            .maxByOrNull { candidate -> candidate.path.length }
        return if (parent == null) {
            RegistrationLocation(context.projectRoot.toFile(), target.path)
        } else {
            RegistrationLocation(
                resolveGitDir(context.projectRoot, parent.path),
                target.path.removePrefix("${parent.path}/"),
            )
        }
    }

    private data class RegistrationLocation(val root: java.io.File, val path: String)

    private fun updateProgress(context: SwitchContext, index: Int, total: Int, path: String) {
        context.progressHandle?.apply {
            fraction = index.toDouble() / total
            text2 = path
        }
    }
}
