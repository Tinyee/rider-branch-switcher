package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.diagnosticFingerprint
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/**
 * Updates submodules in parent-first order.
 *
 * Each parent is fully prepared, fetched, checked out, and pulled before its
 * descendants are inspected. This keeps nested `.gitmodules` data current and
 * prevents child repositories from being updated against stale parent state.
 */
class SubmoduleTreeStep : SwitchStep {
    override val name = "switch submodules"
    override val stage = OperationStage.CHECKOUT

    @Suppress("TooGenericExceptionCaught") // preserve completed per-repository state across Git failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val targets = context.preset.targetsFor(SwitchTargetScope.SUBMODULES)
        if (targets.isEmpty()) return StepExecution(StepResult.Success, state)

        val issues = mutableListOf<OperationIssue>()
        var traversal = SubmoduleTraversal(state, loadTopology(context, state))
        try {
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()
                traversal = processTarget(context, targets, target, traversal, issues)
            }
        } catch (error: SwitchStepException) {
            throw error
        } catch (error: RuntimeException) {
            throw SwitchStepException(traversal.state, error)
        }

        val result = if (issues.isEmpty()) StepResult.Success else StepResult.Partial(issues)
        return StepExecution(result, traversal.state)
    }

    private fun processTarget(
        context: SwitchContext,
        targets: List<RepoTarget>,
        target: RepoTarget,
        traversal: SubmoduleTraversal,
        issues: MutableList<OperationIssue>,
    ): SubmoduleTraversal {
        val preparation = prepareTarget(context, targets, target, traversal, issues)
        val directory = preparation.directory ?: return traversal.copy(state = preparation.state)
        return updatePreparedTarget(
            context,
            targets,
            target,
            directory,
            traversal.copy(state = preparation.state),
            issues,
        )
    }

    @Suppress("TooGenericExceptionCaught") // initialization state must survive later validation errors
    private fun prepareTarget(
        context: SwitchContext,
        targets: List<RepoTarget>,
        target: RepoTarget,
        traversal: SubmoduleTraversal,
        issues: MutableList<OperationIssue>,
    ): PreparedSubmodule {
        var nextState = traversal.state
        try {
            if (nextState.isSkipped(target.path)) {
                context.log.info("[skip] ${target.path} - target disabled by an earlier step")
                nextState = disableDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }
            if (!nextState.checkoutSucceeded(".")) {
                issues += OperationIssue(stage, OperationIssueCode.MAIN_CHECKOUT_REQUIRED, target.path)
                nextState = disableTargetAndDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }
            if (traversal.topology.isUnregistered(target.path)) {
                context.log.warn(
                    "[skip] ${target.path} - not registered in current .gitmodules graph; " +
                        "obsolete worktree retained",
                )
                issues += OperationIssue(
                    OperationStage.TOPOLOGY,
                    OperationIssueCode.SUBMODULE_NOT_REGISTERED,
                    target.path,
                )
                nextState = disableTargetAndDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }

            context.log.info("")
            context.log.info("--- ${target.path} - ${target.branch} ---")
            val directory = resolveGitDir(context.projectRoot, target.path)
            val registration = registrationLocation(
                context,
                target,
                traversal.topology.byPath.getValue(target.path),
            )
            val preparation = SubmoduleInitializer.prepare(
                context,
                target,
                directory,
                registration.root,
                registration.path,
            )
            if (preparation.initializedBySwitch) nextState = nextState.withInitializedSubmodule(target.path)
            preparation.issue?.let(issues::add)
            if (!preparation.ready) {
                nextState = disableDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }

            val expectedGitDirectory = expectedSubmoduleGitDirectory(
                context.projectRoot.toFile(),
                traversal.topology.byPath[target.path],
                context.git,
            )
            if (isUnassociatedSubmoduleWorktree(
                    context.projectRoot.toFile(),
                    target.path,
                    directory,
                    preparation.repositoryIdentity,
                    expectedGitDirectory,
                )
            ) {
                context.log.warn(
                    "[skip] ${target.path} - repository is not associated with its superproject; " +
                        "actualGitDir=${preparation.repositoryIdentity?.gitDirectory}, " +
                        "expectedGitDir=$expectedGitDirectory, " +
                        "superproject=${preparation.repositoryIdentity?.superprojectRoot}",
                )
                issues += OperationIssue(
                    OperationStage.TOPOLOGY,
                    OperationIssueCode.REPOSITORY_IDENTITY_CHANGED,
                    target.path,
                )
                nextState = disableDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }

            val checkpoint = context.checkpoint[target.path]
            val currentDeclaredUrl = traversal.topology.byPath[target.path]?.url
            // A target the current main did not register at checkpoint time has no declared URL
            // to compare; once the main branch switches and registers it, the URL appears. That
            // is a new registration, not a repository replacement, so the gate only runs against
            // targets that were already registered when checkpointed.
            if (checkpoint != null && checkpoint.registeredAtCheckpoint && checkpoint.declaredUrl != currentDeclaredUrl) {
                context.log.warn(
                    "[skip] ${target.path} - registered repository remote changed; " +
                        "before=${diagnosticFingerprint(checkpoint.declaredUrl)}, " +
                        "current=${diagnosticFingerprint(currentDeclaredUrl)}",
                )
                issues += OperationIssue(
                    OperationStage.TOPOLOGY,
                    OperationIssueCode.REPOSITORY_REMOTE_CHANGED,
                    target.path,
                )
                nextState = disableDescendants(nextState, targets, target.path)
                return rejectedTarget(context, target, nextState, issues)
            }
            return PreparedSubmodule(nextState, directory)
        } catch (error: RuntimeException) {
            throw SwitchStepException(nextState, error)
        }
    }

    @Suppress("TooGenericExceptionCaught") // checkout state must survive pull, sync, or stash errors
    private fun updatePreparedTarget(
        context: SwitchContext,
        targets: List<RepoTarget>,
        target: RepoTarget,
        directory: File,
        traversal: SubmoduleTraversal,
        issues: MutableList<OperationIssue>,
    ): SubmoduleTraversal {
        var nextState = traversal.state
        var topology = traversal.topology
        try {
            fetchIfEnabled(context, target, directory, issues)
            val checkout = BranchCheckout.execute(context, target, directory, nextState)
            nextState = checkout.state
            issues += checkout.issues
            if (!checkout.succeeded) {
                nextState = disableDescendants(nextState, targets, target.path)
                return SubmoduleTraversal(nextState, topology)
            }

            pullIfEnabled(context, target, directory, issues)
            if (hasDescendants(targets, target.path)) {
                val sync = context.git.submoduleSync(directory)
                if (!sync.ok) {
                    context.log.warn("nested submodule sync failed: ${sync.diagnostic()} (${target.path})")
                    issues += OperationIssue(
                        OperationStage.SUBMODULE_SYNC,
                        OperationIssueCode.SUBMODULE_SYNC_FAILED,
                        target.path,
                        diagnostic = sync.diagnostic(),
                    )
                    nextState = disableDescendants(nextState, targets, target.path)
                }
                topology = loadTopology(context, nextState)
            }
            return SubmoduleTraversal(nextState, topology)
        } catch (error: RuntimeException) {
            throw SwitchStepException(nextState, error)
        }
    }

    private fun fetchIfEnabled(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        issues: MutableList<OperationIssue>,
    ) {
        if (!context.options.fetchFirst) return
        val fetch = context.git.fetch(directory)
        if (fetch.ok) return
        context.log.warn("fetch warn: ${fetch.diagnostic()} (${target.path})")
        issues += OperationIssue(
            OperationStage.FETCH,
            OperationIssueCode.FETCH_FAILED,
            target.path,
            diagnostic = fetch.diagnostic(),
        )
    }

    private fun pullIfEnabled(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        issues: MutableList<OperationIssue>,
    ) {
        if (!context.options.pull) return
        val pull = context.git.pullFf(directory, target.branch)
        if (pull.ok) {
            context.log.info("pull ok - ${target.path}")
            return
        }
        context.log.warn("pull failed (kept local): ${pull.diagnostic()}")
        issues += OperationIssue(
            OperationStage.PULL,
            OperationIssueCode.PULL_FAILED,
            target.path,
            diagnostic = pull.diagnostic(),
        )
    }

    private fun rejectedTarget(
        context: SwitchContext,
        target: RepoTarget,
        state: SwitchState,
        issues: MutableList<OperationIssue>,
    ) = PreparedSubmodule(state, directory = null)

    private fun loadTopology(context: SwitchContext, state: SwitchState): SubmoduleTopology =
        if (state.checkoutSucceeded(".")) {
            context.git.loadSubmoduleTopology(context.projectRoot.toFile())
        } else {
            SubmoduleTopology(emptySet(), emptyMap())
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
        target: RepoTarget,
        registration: SubmoduleRegistration,
    ): RegistrationLocation {
        return if (registration.parentPath == ".") {
            RegistrationLocation(context.projectRoot.toFile(), target.path)
        } else {
            RegistrationLocation(
                resolveGitDir(context.projectRoot, registration.parentPath),
                target.path.removePrefix("${registration.parentPath}/"),
            )
        }
    }

    private data class SubmoduleTraversal(val state: SwitchState, val topology: SubmoduleTopology)
    private data class PreparedSubmodule(val state: SwitchState, val directory: File?)
    private data class RegistrationLocation(val root: File, val path: String)

    private fun updateProgress(context: SwitchContext, index: Int, total: Int, path: String) {
        context.progressHandle?.updateProgress(index, total, context.projectRoot, path)
    }
}
