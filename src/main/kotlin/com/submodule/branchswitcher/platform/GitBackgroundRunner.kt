package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Platform task outcome that preserves a value completed just before
 * cancellation, when one exists.
 */
sealed class GitBackgroundResult<out T> {
    data class Completed<T>(val value: T) : GitBackgroundResult<T>()
    data class Cancelled<T>(val value: T? = null) : GitBackgroundResult<T>()
    data class Failed(val error: RuntimeException) : GitBackgroundResult<Nothing>()
}

private data class CompletedValue<T>(val value: T)

private sealed interface GitTaskState<out T> {
    data object Running : GitTaskState<Nothing>
    data class Completed<T>(val result: CompletedValue<T>) : GitTaskState<T>
    data class Cancelled<T>(val result: CompletedValue<T>?) : GitTaskState<T>
}

/** Atomically combines task completion and cancellation callbacks into one outcome. */
internal class GitTaskOutcome<T> {
    private val state = AtomicReference<GitTaskState<T>>(GitTaskState.Running)

    fun recordCompletion(value: T) {
        val completed = CompletedValue(value)
        update { current ->
            when (current) {
                GitTaskState.Running -> GitTaskState.Completed(completed)
                is GitTaskState.Completed -> current
                is GitTaskState.Cancelled -> GitTaskState.Cancelled(completed)
            }
        }
    }

    fun recordCancellation() {
        update { current ->
            when (current) {
                GitTaskState.Running -> GitTaskState.Cancelled(null)
                is GitTaskState.Completed -> GitTaskState.Cancelled(current.result)
                is GitTaskState.Cancelled -> current
            }
        }
    }

    fun result(): GitBackgroundResult<T> = when (val current = state.get()) {
        GitTaskState.Running -> GitBackgroundResult.Cancelled()
        is GitTaskState.Completed -> GitBackgroundResult.Completed(current.result.value)
        is GitTaskState.Cancelled -> GitBackgroundResult.Cancelled(current.result?.value)
    }

    private inline fun update(transform: (GitTaskState<T>) -> GitTaskState<T>) {
        while (true) {
            val current = state.get()
            val updated = transform(current)
            if (current === updated || state.compareAndSet(current, updated)) return
        }
    }
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
    /**
     * Executes [block] once and closes its Git session on every outcome.
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
        val outcome = GitTaskOutcome<T>()
        return try {
            TaskBridge.runBackground(
                taskRunner,
                project,
                title,
                true,
                block = { indicator ->
                    outcome.recordCompletion(block(indicator, operation))
                },
                onCancel = {
                    outcome.recordCancellation()
                    operation.cancel()
                },
                onFinished = null,
            )
            outcome.result()
        } catch (_: CancellationException) {
            outcome.recordCancellation()
            outcome.result()
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            outcome.recordCancellation()
            outcome.result()
        } catch (e: RuntimeException) {
            GitBackgroundResult.Failed(e)
        } finally {
            // Cancellation stops the process; close releases ownership of the
            // whole session. Both actions remain idempotent and independently required.
            operation.close()
        }
    }
}
