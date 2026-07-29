package com.submodule.branchswitcher.workflow

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.GitBackgroundResult
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.switch.DeriveBranchExecutor
import com.submodule.branchswitcher.switch.DeriveResult
import java.nio.file.Path

/** Final derive workflow outcome consumed by UI presentation code. */
data class DeriveRunResult(
    val cancelled: Boolean,
    val execution: DeriveResult?,
    val rollbackFailures: List<String> = emptyList(),
)

private data class BackgroundDeriveOutcome(
    val deriveResult: DeriveResult,
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
    private val project: Project,
    private val projectRoot: Path,
    private val gitClient: GitOperationProvider,
    private val taskRunner: TaskBridge.TaskRunner = TaskBridge.TaskRunner.DEFAULT,
) {
    suspend fun execute(
        title: String,
        preset: Preset,
        branchName: String,
        log: AppLogger,
    ): DeriveRunResult {
        val backgroundResult = GitBackgroundRunner(project, gitClient, taskRunner).run(title) { indicator, gitOperation ->
            indicator.isIndeterminate = true
            val executor = DeriveBranchExecutor(
                projectRoot = projectRoot,
                log = log,
                git = gitOperation,
                cancelled = { indicator.isCanceled },
                classifier = platformCancellationClassifier,
            )
            val deriveResult = executor.execute(preset, branchName)
            val rollbackFailures = if (!deriveResult.allOk && deriveResult.succeeded.isNotEmpty()) {
                log.activity("[derive] rolling back ${deriveResult.succeeded.size} succeeded repo(s)...")
                executor.rollbackSucceeded(deriveResult, branchName)
            } else {
                emptyList()
            }
            BackgroundDeriveOutcome(deriveResult, rollbackFailures)
        }

        return when (backgroundResult) {
            is GitBackgroundResult.Completed -> DeriveRunResult(
                cancelled = false,
                execution = backgroundResult.value.deriveResult,
                rollbackFailures = backgroundResult.value.rollbackFailures,
            )
            is GitBackgroundResult.Cancelled -> {
                log.info("[cancelled] derive cancelled by user")
                val deriveResult = backgroundResult.value?.deriveResult
                DeriveRunResult(
                    cancelled = true,
                    execution = deriveResult,
                    rollbackFailures = rollbackAfterCancellation(deriveResult, branchName, log),
                )
            }
            is GitBackgroundResult.Failed -> {
                val error = backgroundResult.error
                log.error("derive: ${error.javaClass.simpleName}: ${error.message}")
                DeriveRunResult(cancelled = false, execution = null)
            }
        }
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
        val rollbackOperation = gitClient.openOperation()
        return try {
            log.activity(
                "[derive] rolling back ${execution.succeeded.size} succeeded repo(s) after cancel...",
            )
            DeriveBranchExecutor(
                projectRoot = projectRoot,
                log = log,
                git = rollbackOperation,
                classifier = platformCancellationClassifier,
            ).rollbackSucceeded(execution, branchName)
        } catch (e: Exception) {
            log.error("derive rollback after cancel: ${e.javaClass.simpleName}: ${e.message}")
            listOf("(exception)")
        } finally {
            rollbackOperation.close()
        }
    }
}
