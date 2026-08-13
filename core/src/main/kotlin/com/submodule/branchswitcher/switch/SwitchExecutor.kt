package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
    val repositoryId: String? = null,
    val declaredUrl: String? = null,
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
) {
    val ok: Boolean get() = status == SwitchExecutionStatus.SUCCESS
    val cancelled: Boolean get() = status == SwitchExecutionStatus.CANCELLED
}

/**
 * Orchestrates a branch switch by running a pipeline of [SwitchStep]s.
 *
 * Pipeline order (intentional):
 * 1. [DirtyHandlingStep] - stash/skip/force known repos before any branch changes
 * 2. Update main (fetch, checkout, pull) so its .gitmodules and gitlinks are current
 * 3. [SubmoduleSyncStep] - align URLs from the updated .gitmodules
 * 4. Update submodules (fetch existing repos, initialize missing repos, checkout, pull)
 *
 * Records a [CheckpointEntry] before switching for rollback support.
 */
class SwitchExecutor @JvmOverloads constructor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
    private val cancellationHandle: CancellationHandle? = null,
    private val progressHandle: ProgressHandle? = null,
    private val cancelled: (() -> Boolean)? = null,
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
    private val preApprovedSubmoduleInit: Set<String> = emptySet(),
    private val steps: List<SwitchStep> = listOf(
        DirtyHandlingStep(),
        FetchStep(SwitchTargetScope.MAIN),
        CheckoutStep(),
        PullStep(SwitchTargetScope.MAIN),
        SubmoduleSyncStep(),
        SubmoduleTreeStep(),
    ),
) {
    private val checkpointRecorder = SwitchCheckpointRecorder(projectRoot, log, git)

    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized through the injected classifier
    fun execute(request: ResolvedSwitchRequest): SwitchExecutionResult {
        val preset = request.preset
        val options = request.options
        log.activity("=== switching to preset: ${preset.name} ===")
        var switchState = SwitchState()
        // Fail closed: every existing repository needs a known branch and SHA
        // before the first mutation, otherwise rollback could only be partial.
        // Checkpoint queries can fail (Git process capacity, start failure, permission),
        // so they must not escape as an unhandled exception that loses the repository
        // path and structured diagnostic the caller relies on.
        val switchCheckpoint = try {
            checkpointRecorder.record(preset)
        } catch (error: GitQueryException) {
            // The platform classifier recognizes a cancelled/interrupted Git query as
            // cancellation; it must not be downgraded to a FAILED/GIT_QUERY_FAILED result.
            if (cancellationClassifier.isCancellation(error)) throw error
            log.error("[checkpoint] switch aborted: git query failed: ${error.result.diagnostic()}")
            return checkpointFailure(switchState, OperationIssueCode.GIT_QUERY_FAILED, error.result.diagnostic())
        } catch (error: RuntimeException) {
            if (cancellationClassifier.isCancellation(error)) throw error
            log.logFailure("[checkpoint] switch aborted: unable to record every existing repository", error)
            return checkpointFailure(
                switchState,
                OperationIssueCode.CHECKPOINT_UNAVAILABLE,
                "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        if (switchCheckpoint == null) {
            log.error("[checkpoint] switch aborted: unable to record every existing repository")
            log.activity("=== done with errors ===")
            return SwitchExecutionResult(
                status = SwitchExecutionStatus.FAILED,
                checkpoint = null,
                state = switchState,
                issues = listOf(
                    OperationIssue(
                        stage = OperationStage.CHECKPOINT,
                        code = OperationIssueCode.CHECKPOINT_UNAVAILABLE,
                        repositoryPath = ".",
                        severity = OperationIssueSeverity.ERROR,
                    ),
                ),
            )
        }

        val context = createContext(preset, options, switchCheckpoint)

        context.progressHandle?.isIndeterminate = false

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

    /** Executes the pipeline steps and folds their outcomes into one structured result. */
    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized through the injected classifier
    private fun runSteps(
        context: SwitchContext,
        switchCheckpoint: Map<String, CheckpointEntry>,
        initialState: SwitchState,
    ): SwitchExecutionResult {
        var executionStatus = SwitchExecutionStatus.SUCCESS
        var switchState = initialState
        val issues = mutableListOf<OperationIssue>()
        for (step in steps) {
            context.progressHandle?.text = step.name
            try {
                cancellationHandle?.checkCanceled()
            } catch (e: RuntimeException) {
                if (!cancellationClassifier.isCancellation(e)) throw e
                git.cancel()
                log.info("[cancelled] before step: ${step.name}")
                executionStatus = SwitchExecutionStatus.CANCELLED
                break
            }
            if (context.cancelled()) {
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
                        repositoryPath = lock.repositoryPath,
                        severity = OperationIssueSeverity.ERROR,
                        diagnostic = indexLockBlockedDiagnostic(lock.lockPath),
                        lockPath = lock.lockPath,
                    )
                    executionStatus = SwitchExecutionStatus.FAILED
                } else if (cancellationClassifier.isCancellation(error)) {
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
                is StepResult.Fatal -> {
                    log.error("${stepResult.issue.code}: ${stepResult.issue.diagnostic.orEmpty()}")
                    issues += stepResult.issue
                    executionStatus = SwitchExecutionStatus.FAILED
                    break
                }
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
        if (executionStatus == SwitchExecutionStatus.FAILED && switchState.stashesSnapshot().isNotEmpty()) {
            // A failed step can happen after DirtyHandlingStep has hidden user WIP.
            // Restore it while the write session is still usable; otherwise the failed
            // switch leaves the tree clean and the only copy silently in refs/stash.
            val restore = try {
                restoreTrackedStashes(projectRoot, context.git, log, switchState)
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
                    switchState,
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
            switchState = restore.state
            issues += restore.issues
        }
        log.info("")
        log.activity(
            if (executionStatus == SwitchExecutionStatus.SUCCESS) "=== done ===" else "=== done with errors ===",
        )
        return SwitchExecutionResult(executionStatus, switchCheckpoint, switchState, issues)
    }

    private fun createContext(
        preset: Preset,
        options: com.submodule.branchswitcher.model.SwitchOptions,
        checkpoint: Map<String, CheckpointEntry>,
    ): SwitchContext {
        val targetPaths = preset.targets().associate { target ->
            val directory = resolveGitDir(projectRoot, target.path)
            directory.canonicalPath to target.path
        }
        val guardedGit = WriteGuardGitClient(
            git,
            repositoryPath = { directory -> targetPaths[directory.canonicalPath] ?: directory.path },
        )
        return SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = guardedGit,
            log = log,
            cancellationHandle = cancellationHandle,
            progressHandle = progressHandle,
            cancelled = { cancelled?.invoke() == true || cancellationHandle?.isCanceled == true },
            confirmBeforeInit = options.confirmBeforeInit,
            preApprovedSubmoduleInit = preApprovedSubmoduleInit,
            checkpoint = checkpoint,
        )
    }

    /**
     * Structured pre-mutation failure: checkpoint recording failed, so no rollback can be
     * attempted and no checkpoint is retained.
     */
    private fun checkpointFailure(
        switchState: SwitchState,
        code: OperationIssueCode,
        diagnostic: String,
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
                stage = OperationStage.CHECKPOINT,
                code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                repositoryPath = block.repositoryPath,
                severity = OperationIssueSeverity.ERROR,
                diagnostic = indexLockBlockedDiagnostic(block.lockPath),
                lockPath = block.lockPath,
            )
        }
    }

    private fun safeBlockingLockIssues(context: SwitchContext, preset: Preset): List<OperationIssue>? =
        try {
            blockingLockIssues(context, preset)
        } catch (e: GitQueryException) {
            // A cancelled/interrupted lock probe is a user cancel, not a query failure.
            if (cancellationClassifier.isCancellation(e)) throw e
            val issue = OperationIssue(
                stage = OperationStage.CHECKPOINT,
                code = OperationIssueCode.GIT_QUERY_FAILED,
                repositoryPath = ".",
                severity = OperationIssueSeverity.ERROR,
                diagnostic = e.result.diagnostic(),
            )
            log.error("[index.lock] preflight query failed: ${issue.diagnostic}")
            listOf(issue)
        }

}
