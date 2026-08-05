package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.DeriveBranchExecutor
import com.submodule.branchswitcher.switch.DeriveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Final derive workflow outcome consumed by UI presentation code. */
data class DeriveRunResult(
    val operationId: String,
    val cancelled: Boolean,
    val execution: DeriveResult?,
    val rollbackFailures: List<String> = emptyList(),
)

private data class BackgroundDeriveOutcome(
    val deriveResult: DeriveResult,
    val rollbackAttempted: Boolean,
    val rollbackFailures: List<String>,
)

/**
 * Owns the background Git lifecycle for deriving one branch across a preset.
 *
 * The core executor decides which repositories may be modified. This runner
 * adds platform cancellation and guarantees that cancellation rollback uses a
 * fresh Git session after the cancelled session has been closed.
 */
class DeriveBranchRunner(
    private val projectRoot: Path,
    private val operations: GitOperationRunner,
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
) {
    suspend fun execute(
        title: String,
        preset: Preset,
        branchName: String,
        log: AppLogger,
    ): DeriveRunResult = withContext(Dispatchers.IO) {
        executeOnWorker(title, preset, branchName, log)
    }

    private suspend fun executeOnWorker(
        title: String,
        preset: Preset,
        branchName: String,
        log: AppLogger,
    ): DeriveRunResult {
        val operationId = newOperationId("derive")
        val operationLog = log.withContext(operationId)
        operationLog.activity(
            "operation started: root=${projectRoot.toAbsolutePath().normalize()}, " +
                "preset='${preset.name}', branch=$branchName, targets=${preset.targets().size}",
        )
        preset.targets().forEach { target ->
            operationLog.info("baseline target: path=${target.path}, branch=${target.branch}")
        }
        val backgroundResult = operations.run(title) { indicator, gitOperation ->
            indicator.isIndeterminate = true
            operationLog.logGitRuntime(gitOperation, projectRoot.toFile(), cancellationClassifier)
            val executor = DeriveBranchExecutor(
                projectRoot = projectRoot,
                log = operationLog,
                git = gitOperation,
                cancelled = { indicator.isCanceled },
                classifier = cancellationClassifier,
            )
            val deriveResult = executor.execute(preset, branchName)
            val operationCancelled = indicator.isCanceled || deriveResult.cancelled
            val partialFailure = !deriveResult.allOk && deriveResult.succeeded.isNotEmpty()
            val rollbackAttempted = !operationCancelled && partialFailure
            val rollbackFailures = if (rollbackAttempted) {
                operationLog.activity("[derive] rolling back ${deriveResult.succeeded.size} succeeded repo(s)...")
                executor.rollbackSucceeded(deriveResult, branchName)
            } else {
                emptyList()
            }
            BackgroundDeriveOutcome(deriveResult, rollbackAttempted, rollbackFailures)
        }

        val result = when (backgroundResult) {
            is GitOperationResult.Completed -> DeriveRunResult(
                operationId = operationId,
                cancelled = false,
                execution = backgroundResult.value.deriveResult,
                rollbackFailures = backgroundResult.value.rollbackFailures,
            )
            is GitOperationResult.Cancelled -> {
                operationLog.info("[cancelled] derive cancelled by user")
                val backgroundOutcome = backgroundResult.value
                val deriveResult = backgroundOutcome?.deriveResult
                DeriveRunResult(
                    operationId = operationId,
                    cancelled = true,
                    execution = deriveResult,
                    rollbackFailures = if (backgroundOutcome?.rollbackAttempted == true) {
                        backgroundOutcome.rollbackFailures
                    } else {
                        rollbackAfterCancellation(deriveResult, branchName, operationLog)
                    },
                )
            }
            is GitOperationResult.Failed -> {
                val error = backgroundResult.error
                operationLog.error("derive workflow failed", error)
                DeriveRunResult(operationId = operationId, cancelled = false, execution = null)
            }
        }
        operationLog.activity(
            "operation finished: cancelled=${result.cancelled}, " +
                "succeeded=${result.execution?.succeeded?.size ?: 0}, " +
                "failed=${result.execution?.failedOutcomes?.size ?: 0}, " +
                "rollbackFailures=${result.rollbackFailures.size}",
        )
        return result
    }

    @Suppress("TooGenericExceptionCaught") // cancellation recovery returns a report instead of escaping
    private fun rollbackAfterCancellation(
        execution: DeriveResult?,
        branchName: String,
        log: AppLogger,
    ): List<String> {
        if (execution == null || execution.succeeded.isEmpty()) {
            return emptyList()
        }

        // The cancelled background session cannot run rollback commands. Open a
        // fresh session after GitBackgroundRunner has closed the cancelled one.
        val rollbackOperation = try {
            operations.openOperation()
        } catch (e: RuntimeException) {
            log.error("derive rollback session could not be opened", e)
            return listOf("(session)")
        }
        return try {
            log.activity(
                "[derive] rolling back ${execution.succeeded.size} succeeded repo(s) after cancel...",
            )
            DeriveBranchExecutor(
                projectRoot = projectRoot,
                log = log,
                git = rollbackOperation,
                classifier = cancellationClassifier,
            ).rollbackSucceeded(execution, branchName)
        } catch (e: Exception) {
            log.error("derive rollback after cancel failed", e)
            listOf("(exception)")
        } finally {
            rollbackOperation.close()
        }
    }
}
