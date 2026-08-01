package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
    val repositoryId: String? = null,
    val remoteUrl: String? = null,
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
    val failures: Map<String, String> = emptyMap(),
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
    private val onConfirmSubmoduleInit: ((String) -> Boolean)? = null,
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
        val switchCheckpoint = checkpointRecorder.record(preset)
        if (switchCheckpoint == null) {
            log.error("[checkpoint] switch aborted: unable to record every existing repository")
            log.activity("=== done with errors ===")
            return SwitchExecutionResult(
                status = SwitchExecutionStatus.FAILED,
                checkpoint = null,
                state = switchState,
                failures = mapOf("." to "unable to record every existing repository"),
            )
        }

        val context = SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = git,
            log = log,
            cancellationHandle = cancellationHandle,
            progressHandle = progressHandle,
            cancelled = { cancelled?.invoke() == true || cancellationHandle?.isCanceled == true },
            confirmBeforeInit = options.confirmBeforeInit,
            onConfirmSubmoduleInit = onConfirmSubmoduleInit,
            checkpoint = switchCheckpoint,
        )

        context.progressHandle?.isIndeterminate = false

        var executionStatus = SwitchExecutionStatus.SUCCESS
        val failures = linkedMapOf<String, String>()
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
                if (cancellationClassifier.isCancellation(error)) {
                    git.cancel()
                    log.info("[cancelled] during step: ${step.name}")
                    executionStatus = SwitchExecutionStatus.CANCELLED
                } else {
                    val reason = "${error.javaClass.simpleName}: ${error.message}"
                    log.error("[failed] ${step.name}: $reason")
                    failures[step.name] = reason
                    executionStatus = SwitchExecutionStatus.FAILED
                }
                break
            }
            switchState = stepExecution.state
            when (val stepResult = stepExecution.result) {
                is StepResult.Fatal -> {
                    log.error(" ${stepResult.reason}")
                    failures[step.name] = stepResult.reason
                    executionStatus = SwitchExecutionStatus.FAILED
                    break
                }
                is StepResult.Partial -> {
                    stepResult.failures.forEach { (path, message) ->
                        log.warn("$path: $message")
                    }
                    failures.putAll(stepResult.failures)
                    if (executionStatus == SwitchExecutionStatus.SUCCESS) {
                        executionStatus = SwitchExecutionStatus.PARTIAL
                    }
                }
                is StepResult.Success -> { /* continue */ }
            }
        }
        log.info("")
        log.activity(
            if (executionStatus == SwitchExecutionStatus.SUCCESS) "=== done ===" else "=== done with errors ===",
        )
        return SwitchExecutionResult(executionStatus, switchCheckpoint, switchState, failures)
    }

}
