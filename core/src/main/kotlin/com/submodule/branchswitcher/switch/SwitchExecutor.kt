package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.IndexLockBlockedException
import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import java.io.File
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
    val repositoryId: String? = null,
    val declaredUrl: String? = null,
    /**
     * True when the target was a registered submodule of the current main at checkpoint
     * time. A target the preset registers only after the main branch switches is recorded
     * with [declaredUrl] == null and this set to false, so the remote-change gate does not
     * mistake a new registration for a repository replacement.
     */
    val registeredAtCheckpoint: Boolean = true,
)

enum class SwitchExecutionStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELLED,
}

/**
 * Complete observable outcome of a switch pipeline.
 *
 * [checkpoint] describes the state before mutation. [state] describes side
 * effects that actually completed and is used for pending stash recovery.
 */
data class SwitchExecutionResult(
    val status: SwitchExecutionStatus,
    val checkpoint: Map<String, CheckpointEntry>?,
    val state: SwitchState,
    val issues: List<OperationIssue> = emptyList(),
    /**
     * True when the end-of-pipeline stash restore stopped because the user cancelled.
     * The status stays SUCCESS/PARTIAL (a completed switch must not be rolled back), so
     * this flag lets the caller distinguish "the restore needs a retry" from "the user
     * chose to stop restoring".
     */
    val stashRestoreInterrupted: Boolean = false,
) {
    val ok: Boolean get() = status == SwitchExecutionStatus.SUCCESS
    val cancelled: Boolean get() = status == SwitchExecutionStatus.CANCELLED
}

/**
 * Orchestrates a branch switch by running a fixed pipeline of [SwitchStep]s.
 *
 * Pipeline order (intentional, fixed):
 * 1. Fetch the main repo so collision revalidation sees a fresh target ref
 * 2. [DiscardUntrackedCollisionStep] - always present at this position (Spec 4): a no-op
 *    when no discards are approved; revalidates + isolates main collision files after the
 *    fetch and before the WIP stash, so they are never swept into `-u`
 * 3. [DirtyHandlingStep] - stash/skip/force the main repo's dirty tree
 * 4. Update main (checkout, pull) so its .gitmodules and gitlinks are current
 * 5. [SubmoduleSyncStep] - align URLs from the updated .gitmodules
 * 6. Update submodules (topology-confirmed: dirty handling, collision discard, stash, checkout)
 *
 * Records a [CheckpointEntry] before switching for rollback support.
 */
class SwitchExecutor @JvmOverloads constructor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
    private val operationControl: OperationControl? = null,
    private val progressHandle: ProgressHandle? = null,
    private val preApprovedSubmoduleInit: Set<String> = emptySet(),
    private val collisionDiscards: Map<String, Set<String>> = emptyMap(),
) {
    private val checkpointRecorder = SwitchCheckpointRecorder(projectRoot, log, git)

    /** Canonical repository directory -> preset target path (".", "SubA", ...), set per execute. */
    private var targetPaths: Map<String, String> = emptyMap()

    /** Maps a repository directory from a git-layer exception back to its preset target path. */
    private fun repositoryPathFor(workDir: File): String =
        targetPaths[runCatching { workDir.canonicalPath }.getOrElse { workDir.path }]
            ?: workDir.path

    /**
     * The fixed production pipeline. The discard step is always present at this exact
     * position (Spec 4): a no-op when no discards are approved, it runs only for the main
     * repository after the fetch and before the WIP stash, and it never processes a
     * submodule (submodule isolation happens inside [SubmoduleTreeStep] after the
     * topology gate).
     */
    private val pipeline: List<SwitchStep> = listOf(
        FetchStep(SwitchTargetScope.MAIN),
        DiscardUntrackedCollisionStep(),
        DirtyHandlingStep(),
        CheckoutStep(),
        PullStep(SwitchTargetScope.MAIN),
        SubmoduleSyncStep(),
        SubmoduleTreeStep(),
    )

    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized as OperationCancelledException
    fun execute(request: ResolvedSwitchRequest): SwitchExecutionResult {
        val preset = request.preset
        val options = request.options
        log.activity("=== switching to preset: ${preset.name} ===")
        val switchState = SwitchState()
        val switchCheckpoint = when (val checkpoint = recordCheckpoint(preset, switchState)) {
            is Checkpoint.Recorded -> checkpoint.entries
            is Checkpoint.Failed -> return checkpoint.result
        }

        // One full-UUID operation id per execution scopes every stash message. A short
        // (32-bit) log id is fine for human log lines but too weak to back the "a retained
        // stash from an earlier switch can never be matched" guarantee, so the stash identity
        // uses the full 128-bit UUID.
        val context = createContext(preset, options, switchCheckpoint, java.util.UUID.randomUUID().toString())

        context.progressHandle?.isIndeterminate = false

        // Re-running a preset that is already fully applied (every target on its branch
        // with a clean tree) is a no-op. Short-circuit so it does not re-stash clean WIP
        // or re-pull, which would accumulate stash backups on every repetition.
        if (alreadyAtTargetState(context, preset)) {
            log.info("[no-op] all targets already on their branches and clean; skipping pipeline")
            log.activity("=== done ===")
            return SwitchExecutionResult(
                status = SwitchExecutionStatus.SUCCESS,
                checkpoint = switchCheckpoint,
                state = switchState,
            )
        }

        // A stale git index.lock makes every write fail (checkout, pull, stash) and
        // `git stash` fails on it silently. Surface any existing lock before the first
        // mutation so the user sees exactly which repository is blocked.
        val lockIssues = safeBlockingLockIssues(context, preset)
        if (lockIssues != null) {
            log.activity("=== done with errors ===")
            return SwitchExecutionResult(
                status = SwitchExecutionStatus.FAILED,
                checkpoint = switchCheckpoint,
                state = switchState,
                issues = lockIssues,
            )
        }

        return runSteps(context, switchCheckpoint, switchState)
    }

    /** Result of checkpoint recording: either the recorded entries or an already-final failure result. */
    private sealed interface Checkpoint {
        data class Recorded(val entries: Map<String, CheckpointEntry>) : Checkpoint
        data class Failed(val result: SwitchExecutionResult) : Checkpoint
    }

    /**
     * Records every existing repository's pre-switch state. Fail closed: every repository
     * needs a known branch and SHA before the first mutation, otherwise rollback could only
     * be partial. Checkpoint queries can fail (Git process capacity, start failure,
     * permission), so failures are mapped to a structured result instead of escaping as an
     * unhandled exception that loses the repository path and diagnostic the caller relies on.
     */
    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized as OperationCancelledException
    private fun recordCheckpoint(preset: Preset, switchState: SwitchState): Checkpoint = try {
        val entries = checkpointRecorder.record(preset)
        if (entries != null) {
            Checkpoint.Recorded(entries)
        } else {
            log.error("[checkpoint] switch aborted: unable to record every existing repository")
            log.activity("=== done with errors ===")
            Checkpoint.Failed(
                checkpointFailure(switchState, OperationIssueCode.CHECKPOINT_UNAVAILABLE, null),
            )
        }
    } catch (error: GitQueryException) {
        // A cancelled/interrupted Git query surfaces as OperationCancelledException from the
        // git read boundary, so this catch is only reached by real query failures.
        log.error("[checkpoint] switch aborted: git query failed: ${error.result.diagnostic()}")
        Checkpoint.Failed(
            checkpointFailure(switchState, OperationIssueCode.GIT_QUERY_FAILED, error.result.diagnostic()),
        )
    } catch (error: OperationCancelledException) {
        // A cancelled checkpoint read is a user cancel, never a checkpoint failure.
        throw error
    } catch (error: RuntimeException) {
        log.logFailure("[checkpoint] switch aborted: unable to record every existing repository", error)
        Checkpoint.Failed(
            checkpointFailure(
                switchState,
                OperationIssueCode.CHECKPOINT_UNAVAILABLE,
                "${error.javaClass.simpleName}: ${error.message}",
            ),
        )
    }

    /** Executes the pipeline steps and folds their outcomes into one structured result. */
    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized through the injected classifier
    private fun runSteps(
        context: SwitchContext,
        switchCheckpoint: Map<String, CheckpointEntry>,
        initialState: SwitchState,
    ): SwitchExecutionResult {
        var executionStatus = SwitchExecutionStatus.SUCCESS
        var switchState = initialState
        var stashRestoreInterrupted = false
        val issues = mutableListOf<OperationIssue>()
        for (step in pipeline) {
            context.progressHandle?.text = step.name
            try {
                operationControl?.checkCancelled()
            } catch (e: OperationCancelledException) {
                git.cancel() // terminate in-flight command if any
                log.info("[cancelled] before step: ${step.name}")
                executionStatus = SwitchExecutionStatus.CANCELLED
                break
            }
            log.info("--- ${step.name} ---")
            val stepExecution = try {
                step.execute(context, switchState)
            } catch (e: RuntimeException) {
                val stepFailure = e as? SwitchStepException
                switchState = stepFailure?.latestState ?: switchState
                val error = stepFailure?.cause ?: e
                val lock = error as? IndexLockBlockedException
                if (lock != null) {
                    issues += OperationIssue(
                        stage = step.stage,
                        code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                        repositoryPath = repositoryPathFor(lock.workDir),
                        severity = OperationIssueSeverity.ERROR,
                        diagnostic = indexLockBlockedDiagnostic(lock.lockPath),
                        lockPath = lock.lockPath,
                    )
                    executionStatus = SwitchExecutionStatus.FAILED
                } else if (error is OperationCancelledException) {
                    git.cancel()
                    log.info("[cancelled] during step: ${step.name}")
                    executionStatus = SwitchExecutionStatus.CANCELLED
                } else {
                    log.logFailure("[failed] ${step.name}", error)
                    issues += OperationIssue(
                        stage = step.stage,
                        code = OperationIssueCode.STEP_FAILED,
                        severity = OperationIssueSeverity.ERROR,
                        diagnostic = "${error.javaClass.simpleName}: ${error.message}",
                    )
                    executionStatus = SwitchExecutionStatus.FAILED
                }
                break
            }
            switchState = stepExecution.state
            when (val stepResult = stepExecution.result) {
                is StepResult.Partial -> {
                    stepResult.issues.forEach { issue ->
                        log.warn("${issue.repositoryPath ?: step.name}: ${issue.code}")
                    }
                    issues += stepResult.issues
                    if (executionStatus == SwitchExecutionStatus.SUCCESS) {
                        executionStatus = SwitchExecutionStatus.PARTIAL
                    }
                }
                is StepResult.Success -> { /* continue */ }
            }
        }
        // Restore the WIP that dirty handling stashed only once the switch outcome is
        // final. SUCCESS and PARTIAL restore inline: all checkouts and pulls are done and
        // the write session is still usable. FAILED and CANCELLED deliberately leave the
        // stashes tracked so SwitchRunner's recovery can roll the repositories back while
        // the trees are still clean and only then re-apply the WIP — restoring earlier
        // would dirty the trees and block the rollback's clean-tree requirement.
        if (executionStatus == SwitchExecutionStatus.SUCCESS ||
            executionStatus == SwitchExecutionStatus.PARTIAL
        ) {
            val restore = restoreCompletedStashes(context, switchState)
            switchState = restore.state
            issues += restore.issues
            stashRestoreInterrupted = restore.interrupted
        }
        log.info("")
        log.activity(
            if (executionStatus == SwitchExecutionStatus.SUCCESS) "=== done ===" else "=== done with errors ===",
        )
        return SwitchExecutionResult(
            executionStatus,
            switchCheckpoint,
            switchState,
            issues,
            stashRestoreInterrupted,
        )
    }

    /**
     * Restores the WIP that dirty handling stashed after a completed pipeline. Honors
     * cancellation inside the restore: a user cancel stops the loop with the remaining WIP
     * preserved (and retryable) in git stash, and is reported via [StashRestoreResult.interrupted].
     * The switch itself already completed, so a cancel here must not flip the status to
     * CANCELLED — recovery would roll the completed switch back and its clean-tree reset
     * would wipe already-restored WIP.
     */
    @Suppress("TooGenericExceptionCaught") // any restore failure must report rather than escape
    private fun restoreCompletedStashes(context: SwitchContext, state: SwitchState): StashRestoreResult = try {
        // A repo that reached its target (and will not be rolled back at this point) has its
        // approved stash dropped — the discard is authorized. A repo whose checkout failed on
        // a partial switch gets its approved stash applied back instead.
        restoreTrackedStashes(
            projectRoot, context.git, log, state,
            control = context.operationControl,
            discardApprovedFor = { path -> state.checkoutSucceeded(path) },
        )
    } catch (error: SwitchStepException) {
        log.logFailure("[stash restore] exception", error.cause)
        StashRestoreResult(
            error.latestState,
            listOf(
                OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_RESTORE_FAILED,
                    severity = OperationIssueSeverity.ERROR,
                    diagnostic = "${error.cause.javaClass.simpleName}: ${error.cause.message}",
                ),
            ),
        )
    } catch (error: RuntimeException) {
        log.logFailure("[stash restore] exception", error)
        StashRestoreResult(
            state,
            listOf(
                OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_RESTORE_FAILED,
                    severity = OperationIssueSeverity.ERROR,
                    diagnostic = "${error.javaClass.simpleName}: ${error.message}",
                ),
            ),
        )
    }

    private fun createContext(
        preset: Preset,
        options: com.submodule.branchswitcher.model.SwitchOptions,
        checkpoint: Map<String, CheckpointEntry>,
        operationId: String,
    ): SwitchContext {
        targetPaths = preset.targets().associate { target ->
            val directory = resolveGitDir(projectRoot, target.path)
            directory.canonicalPath to target.path
        }
        return SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = git,
            log = log,
            operationControl = operationControl,
            progressHandle = progressHandle,
            confirmBeforeInit = options.confirmBeforeInit,
            preApprovedSubmoduleInit = preApprovedSubmoduleInit,
            approvedCollisionDiscards = collisionDiscards,
            checkpoint = checkpoint,
            operationId = operationId,
        )
    }

    /**
     * True when every preset target already sits on its target branch with a clean
     * working tree and pulling is disabled, so the pipeline has nothing to do. The
     * "on branch" fact comes from the freshly recorded [context.checkpoint] (existing
     * repos only — a missing target fails this check), so the only extra probe is one
     * dirty check per target. A failed probe fails closed (returns false) so the normal
     * pipeline still runs and reports the real outcome.
     */
    @Suppress("TooGenericExceptionCaught") // fail closed on any probe failure; cancellation still propagates
    private fun alreadyAtTargetState(context: SwitchContext, preset: Preset): Boolean {
        // fetchFirst refreshes remote-tracking refs even when pull is off, so a
        // fetch-first switch must never be short-circuited.
        if (context.options.pull || context.options.fetchFirst) return false
        return preset.targets().all { target ->
            // A missing or branch-mismatched target means the switch has real work.
            val entry = context.checkpoint[target.path] ?: return@all false
            if (entry.branch != target.branch) return@all false
            try {
                !git.isDirty(resolveGitDir(context.projectRoot, target.path))
            } catch (e: OperationCancelledException) {
                throw e
            } catch (e: RuntimeException) {
                false
            }
        }
    }

    /**
     * Structured pre-mutation failure: checkpoint recording failed, so no rollback can be
     * attempted and no checkpoint is retained.
     */
    private fun checkpointFailure(
        switchState: SwitchState,
        code: OperationIssueCode,
        diagnostic: String?,
    ): SwitchExecutionResult = SwitchExecutionResult(
        status = SwitchExecutionStatus.FAILED,
        checkpoint = null,
        state = switchState,
        issues = listOf(
            OperationIssue(
                stage = OperationStage.CHECKPOINT,
                code = code,
                repositoryPath = ".",
                severity = OperationIssueSeverity.ERROR,
                diagnostic = diagnostic,
            ),
        ),
    )

    /**
     * Issues for every repository whose git `index.lock` already blocks writes, or null if none.
     * Read-only commands are unaffected by a lock, so this is checked once before any mutation.
     */
    private fun blockingLockIssues(context: SwitchContext, preset: Preset): List<OperationIssue>? {
        val blockedLocks = findBlockingIndexLocks(
            context.projectRoot,
            context.git,
            preset.targets().map(RepoTarget::path),
            context.checkpoint,
        )
        if (blockedLocks.isEmpty()) return null
        blockedLocks.forEach { block ->
            val display = if (block.repositoryPath == ".") {
                block.lockPath
            } else {
                "${block.repositoryPath} -> ${block.lockPath}"
            }
            log.error("[index.lock] blocks git writes: $display")
        }
        return blockedLocks.map { block ->
            OperationIssue(
                stage = OperationStage.PRE_MUTATION,
                code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                repositoryPath = block.repositoryPath,
                severity = OperationIssueSeverity.ERROR,
                diagnostic = indexLockBlockedDiagnostic(block.lockPath),
                lockPath = block.lockPath,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // a lock probe must never escape execute(): any failure maps to a structured result
    private fun safeBlockingLockIssues(context: SwitchContext, preset: Preset): List<OperationIssue>? =
        try {
            blockingLockIssues(context, preset)
        } catch (e: GitQueryException) {
            // A cancelled/interrupted lock probe surfaces as OperationCancelledException from
            // the git read boundary, so this catch is only reached by real query failures.
            preMutationLockProbeFailure(e.result.diagnostic())
        } catch (e: OperationCancelledException) {
            // A cancelled lock probe is a user cancel and must propagate, not degrade to
            // a structured pre-mutation failure.
            throw e
        } catch (e: RuntimeException) {
            // Mirrors recordCheckpoint's broader catch: a lock probe that fails for a
            // non-query reason (a path that cannot be resolved) must still surface as a
            // structured pre-mutation result instead of escaping execute() and collapsing
            // the whole switch into a generic failure without recovery.
            preMutationLockProbeFailure("${e.javaClass.simpleName}: ${e.message}")
        }

    private fun preMutationLockProbeFailure(diagnostic: String): List<OperationIssue> {
        val issue = OperationIssue(
            stage = OperationStage.PRE_MUTATION,
            code = OperationIssueCode.GIT_QUERY_FAILED,
            repositoryPath = ".",
            severity = OperationIssueSeverity.ERROR,
            diagnostic = diagnostic,
        )
        log.error("[index.lock] preflight query failed: ${issue.diagnostic}")
        return listOf(issue)
    }

}
