package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
)

enum class SwitchExecutionStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELLED,
}

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
        CheckoutStep(SwitchTargetScope.MAIN),
        PullStep(SwitchTargetScope.MAIN),
        SubmoduleSyncStep(),
        FetchStep(SwitchTargetScope.SUBMODULES),
        CheckoutStep(SwitchTargetScope.SUBMODULES),
        PullStep(SwitchTargetScope.SUBMODULES),
    ),
) {
    private val checkpointRecorder = SwitchCheckpointRecorder(projectRoot, log, git)

    @Suppress("TooGenericExceptionCaught") // platform cancellation type is recognized through the injected classifier
    fun execute(request: ResolvedSwitchRequest): SwitchExecutionResult {
        val preset = request.preset
        val options = request.options
        log.activity("=== switching to preset: ${preset.name} ===")
        var state = SwitchState()
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
        )

        // Do not mutate any existing repository unless it has rollback coverage.
        val checkpoint = checkpointRecorder.record(preset)
        if (checkpoint == null) {
            log.error("[checkpoint] switch aborted: unable to record every existing repository")
            log.activity("=== done with errors ===")
            return SwitchExecutionResult(
                status = SwitchExecutionStatus.FAILED,
                checkpoint = null,
                state = state,
                failures = mapOf("." to "unable to record every existing repository"),
            )
        }

        context.progressHandle?.isIndeterminate = false

        var status = SwitchExecutionStatus.SUCCESS
        val failures = linkedMapOf<String, String>()
        for (step in steps) {
            context.progressHandle?.text = step.name
            try {
                cancellationHandle?.checkCanceled()
            } catch (e: RuntimeException) {
                if (!cancellationClassifier.isCancellation(e)) throw e
                git.cancel()
                log.info("[cancelled] before step: ${step.name}")
                status = SwitchExecutionStatus.CANCELLED
                break
            }
            if (context.cancelled()) {
                git.cancel() // terminate in-flight command if any
                log.info("[cancelled] before step: ${step.name}")
                status = SwitchExecutionStatus.CANCELLED
                break
            }
            log.info("--- ${step.name} ---")
            val execution = step.execute(context, state)
            state = execution.state
            when (val result = execution.result) {
                is StepResult.Fatal -> {
                    log.error(" ${result.reason}")
                    failures[step.name] = result.reason
                    status = SwitchExecutionStatus.FAILED
                    break
                }
                is StepResult.Partial -> {
                    result.failures.forEach { (path, msg) ->
                        log.warn("$path: $msg")
                    }
                    failures.putAll(result.failures)
                    if (status == SwitchExecutionStatus.SUCCESS) {
                        status = SwitchExecutionStatus.PARTIAL
                    }
                }
                is StepResult.Success -> { /* continue */ }
            }
        }
        log.info("")
        log.activity(if (status == SwitchExecutionStatus.SUCCESS) "=== done ===" else "=== done with errors ===")
        return SwitchExecutionResult(status, checkpoint, state, failures)
    }

}
