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
        val traversal = try {
            SubmoduleTraversal(state, loadTopology(context, state))
        } catch (error: SwitchStepException) {
            throw error
        } catch (error: RuntimeException) {
            // Entry topology failure is still a step failure, wrapped consistently with
            // processTarget's error path; nothing has run yet, so the initial state is
            // the recovery state.
            throw SwitchStepException(state, error)
        }
        var current = traversal
        try {
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.operationControl?.checkCancelled()
                current = processTarget(context, targets, target, current, issues)
            }
        } catch (error: SwitchStepException) {
            throw error
        } catch (error: RuntimeException) {
            throw SwitchStepException(current.state, error)
        }

        val result = issues.toStepResult()
        return StepExecution(result, current.state)
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

    @Suppress("TooGenericExceptionCaught", "LongMethod") // initialization state must survive later validation errors; frozen observation point, split deferred
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
                return rejectedTarget(disableDescendants(nextState, targets, target.path))
            }
            if (!nextState.checkoutSucceeded(".")) {
                return reject(
                    nextState,
                    targets,
                    target,
                    OperationIssue(stage, OperationIssueCode.MAIN_CHECKOUT_REQUIRED, target.path),
                    issues,
                )
            }
            if (traversal.topology.isUnregistered(target.path)) {
                context.log.warn(
                    "[skip] ${target.path} - not registered in current .gitmodules graph; " +
                        "obsolete worktree retained",
                )
                return reject(
                    nextState,
                    targets,
                    target,
                    OperationIssue(
                        OperationStage.TOPOLOGY,
                        OperationIssueCode.SUBMODULE_NOT_REGISTERED,
                        target.path,
                    ),
                    issues,
                )
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
                return rejectedTarget(nextState)
            }

            val expectedGitDirectory = expectedSubmoduleGitDirectory(
                context.projectRoot.toFile(),
                traversal.topology.byPath[target.path],
                context.git,
            )
            val blockReason = unassociatedSubmoduleBlockReason(
                context.projectRoot.toFile(),
                target.path,
                directory,
                preparation.repositoryIdentity,
                expectedGitDirectory,
                context.log,
            )
            if (blockReason != null) {
                context.log.warn("[skip] ${target.path} - $blockReason")
                return reject(
                    nextState,
                    targets,
                    target,
                    OperationIssue(
                        OperationStage.TOPOLOGY,
                        OperationIssueCode.REPOSITORY_IDENTITY_CHANGED,
                        target.path,
                    ),
                    issues,
                    disableSelf = false,
                )
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
                return reject(
                    nextState,
                    targets,
                    target,
                    OperationIssue(
                        OperationStage.TOPOLOGY,
                        OperationIssueCode.REPOSITORY_REMOTE_CHANGED,
                        target.path,
                    ),
                    issues,
                    disableSelf = false,
                )
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
            // Submodule isolation runs here, AFTER prepareTarget's topology write gate: a path
            // absent from the new .gitmodules graph was already rejected, so an obsolete worktree
            // is never fetched, stashed, or checked out.
            val approved = context.approvedCollisionDiscards[target.path].orEmpty()
            val atTarget = context.checkpoint[target.path]?.branch == target.branch
            if (approved.isNotEmpty() && !atTarget) {
                when (val stash = stashApprovedCollisions(
                    context, target, directory, nextState, OperationStage.DIRTY_HANDLING,
                )) {
                    is ApprovedStashOutcome.Blocked -> {
                        // Still an approved untracked collision that could not be isolated: fail
                        // closed rather than pretending it was discarded — disable this submodule
                        // and its descendants so its checkout never runs against the collision.
                        issues += stash.issue
                        nextState = disableTargetAndDescendants(nextState, targets, target.path)
                        return SubmoduleTraversal(nextState, topology)
                    }
                    is ApprovedStashOutcome.Proceed ->
                        nextState = stash.state
                }
            }

            val facts = inspectDirtyState(context, directory)
            if (facts != null) {
                val outcome = handleTargetDirtyState(context, target, directory, facts, nextState, issues)
                nextState = outcome.state
                if (outcome.skipped) {
                    nextState = disableDescendants(nextState, targets, target.path)
                    return SubmoduleTraversal(nextState, topology)
                }
            }

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
                    context.log.warn("nested submodule sync failed - ${directory.path}: ${sync.diagnostic()}")
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
        context.log.warn("fetch warn - ${directory.path}: ${fetch.diagnostic()}")
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
        context.log.warn("pull failed (kept local) - ${directory.path}: ${pull.diagnostic()}")
        issues += OperationIssue(
            OperationStage.PULL,
            OperationIssueCode.PULL_FAILED,
            target.path,
            diagnostic = pull.diagnostic(),
        )
    }

    private fun rejectedTarget(state: SwitchState): PreparedSubmodule =
        PreparedSubmodule(state, directory = null)

    /**
     * Records [issue], disables [target] (and its descendants when [disableSelf]) so
     * later steps skip them, and returns the not-ready outcome for the prepare loop.
     * A target that is only individually unusable (identity or remote changed) keeps
     * itself out of [disableSelf] = false and just blocks its descendants.
     */
    private fun reject(
        state: SwitchState,
        targets: List<RepoTarget>,
        target: RepoTarget,
        issue: OperationIssue,
        issues: MutableList<OperationIssue>,
        disableSelf: Boolean = true,
    ): PreparedSubmodule {
        issues += issue
        val nextState = if (disableSelf) {
            disableTargetAndDescendants(state, targets, target.path)
        } else {
            disableDescendants(state, targets, target.path)
        }
        return rejectedTarget(nextState)
    }

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
