package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import kotlinx.coroutines.CancellationException

/**
 * Platform task outcome that preserves a value completed just before
 * cancellation, when one exists.
 */
sealed class GitBackgroundResult<out T> {
    data class Completed<T>(val value: T) : GitBackgroundResult<T>()
    data class Cancelled<T>(val value: T? = null) : GitBackgroundResult<T>()
    data class Failed(val error: RuntimeException) : GitBackgroundResult<Nothing>()
}

/**
 * Runs one IntelliJ background task against an isolated Git operation session.
 *
 * This class is the single owner of open, cancel, exception conversion, and
 * close. Callers provide business work only and must not close the session.
 */
class GitBackgroundRunner(
    private val project: Project,
    private val git: GitOperationProvider,
    private val taskRunner: TaskBridge.TaskRunner = TaskBridge.TaskRunner.DEFAULT,
) {
    private data class ValueBox<T>(val value: T)

    /**
     * Executes [block] once and closes its Git session on every outcome.
     *
     * [ValueBox] distinguishes a successfully completed `null` value from a
     * block that never completed.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> run(
        title: String,
        block: (ProgressIndicator, GitOperationSession) -> T,
    ): GitBackgroundResult<T> {
        val operation = try {
            git.openOperation()
        } catch (_: CancellationException) {
            return GitBackgroundResult.Cancelled()
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            return GitBackgroundResult.Cancelled()
        } catch (e: RuntimeException) {
            return GitBackgroundResult.Failed(e)
        }
        var completed: ValueBox<T>? = null
        var cancelled = false
        return try {
            TaskBridge.runBackground(
                taskRunner,
                project,
                title,
                true,
                block = { indicator ->
                    completed = ValueBox(block(indicator, operation))
                },
                onCancel = {
                    cancelled = true
                    operation.cancel()
                },
                onFinished = null,
            )
            when {
                cancelled || completed == null -> GitBackgroundResult.Cancelled(completed?.value)
                else -> GitBackgroundResult.Completed(requireNotNull(completed).value)
            }
        } catch (_: CancellationException) {
            GitBackgroundResult.Cancelled(completed?.value)
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            GitBackgroundResult.Cancelled(completed?.value)
        } catch (e: RuntimeException) {
            GitBackgroundResult.Failed(e)
        } finally {
            // Cancellation stops the process; close releases ownership of the
            // whole session. Both actions remain idempotent and independently required.
            operation.close()
        }
    }
}
