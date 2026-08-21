package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.DeriveBranchExecutor
import com.submodule.branchswitcher.switch.DeriveResult
import com.submodule.branchswitcher.switch.DeriveRollbackResult
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
    val rollback: DeriveRollbackResult?,
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
        rollbackTitle: String,
        preset: Preset,
        branchName: String,
        log: AppLogger,
    ): DeriveRunResult = withContext(Dispatchers.IO) {
        executeOnWorker(title, rollbackTitle, preset, branchName, log)
    }

    private suspend fun executeOnWorker(
        title: String,
        rollbackTitle: String,
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
            val rollback = if (rollbackAttempted) {
                operationLog.activity("[derive] rolling back ${deriveResult.succeeded.size} succeeded repo(s)...")
                executor.rollbackSucceeded(deriveResult, branchName)
            } else {
                if (operationCancelled && deriveResult.succeeded.isNotEmpty()) {
                    operationLog.warn(
                        "rollback skipped: ${deriveResult.succeeded.size} succeeded repo(s) left (operation cancelled)",
                    )
                }
                null
            }
            BackgroundDeriveOutcome(deriveResult, rollback)
        }

        val result = when (backgroundResult) {
            is GitOperationResult.Completed -> DeriveRunResult(
                operationId = operationId,
                cancelled = false,
                execution = backgroundResult.value.deriveResult,
                rollbackFailures = backgroundResult.value.rollback?.pendingPaths.orEmpty(),
            )
            is GitOperationResult.Cancelled -> {
                operationLog.info("[cancelled] derive cancelled by user")
                val backgroundOutcome = backgroundResult.value
                val deriveResult = backgroundOutcome?.deriveResult
                DeriveRunResult(
                    operationId = operationId,
                    cancelled = true,
                    execution = deriveResult,
                    rollbackFailures = rollbackAfterCancellation(
                        deriveResult,
                        branchName,
                        operationLog,
                        backgroundOutcome?.rollback?.pendingPaths,
                        rollbackTitle,
                    ),
                )
            }
            is GitOperationResult.Failed -> {
                val error = backgroundResult.error
                operationLog.logFailure("derive workflow failed", error)
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
    private suspend fun rollbackAfterCancellation(
        execution: DeriveResult?,
        branchName: String,
        log: AppLogger,
        pendingPaths: List<String>? = null,
        rollbackTitle: String,
    ): List<String> {
        val pathsToRestore = pendingPaths ?: execution?.succeeded.orEmpty()
        if (execution == null || pathsToRestore.isEmpty()) {
            return emptyList()
        }
        log.activity(
            "[derive] rolling back ${pathsToRestore.size} pending repo(s) after cancel...",
        )
        // The cancelled background session cannot run rollback commands. Open a fresh
        // session after the runner has closed the cancelled one — the runner owns
        // open/cancel/close, the same recovery idiom as SwitchRunner.recoverSwitch.
        val rollbackResult = operations.run(rollbackTitle) { _, rollbackOperation ->
            DeriveBranchExecutor(
                projectRoot = projectRoot,
                log = log,
                git = rollbackOperation,
                classifier = cancellationClassifier,
            ).rollbackSucceeded(execution, branchName, pathsToRestore).pendingPaths
        }
        return when (rollbackResult) {
            is GitOperationResult.Completed -> rollbackResult.value
            is GitOperationResult.Cancelled -> {
                log.warn("rollback after cancel was itself cancelled")
                rollbackResult.value ?: listOf("(cancelled)")
            }
            is GitOperationResult.Failed -> {
                log.logFailure("derive rollback after cancel failed", rollbackResult.error)
                listOf("(failed)")
            }
        }
    }
}
