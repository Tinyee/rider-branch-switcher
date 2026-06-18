package com.submodule.branchswitcher

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Bridges IntelliJ's Task API to coroutines, enabling unified `scope.launch { }` usage.
 * Zero call sites use Task.Modal or Task.Backgroundable directly after this migration.
 */
object TaskBridge {

    private val LOG = Logger.getInstance(TaskBridge::class.java)

    /**
     * Injectable boundary for Task scheduling.
     * Tests inject a fake that records callbacks; production delegates to [ProgressManager].
     */
    interface TaskRunner {
        fun run(
            project: Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        )

        companion object {
            val DEFAULT: TaskRunner = object : TaskRunner {
                override fun run(
                    project: Project?,
                    title: String,
                    canBeCancelled: Boolean,
                    onRun: (ProgressIndicator) -> Unit,
                    onFinished: () -> Unit,
                    onCancel: () -> Unit,
                ) {
                    val task = object : Task.Backgroundable(project, title, canBeCancelled) {
                        override fun run(indicator: ProgressIndicator) = onRun(indicator)
                        override fun onFinished() = onFinished()
                        override fun onCancel() = onCancel()
                    }
                    ProgressManager.getInstance().run(task)
                }
            }
        }
    }

    /** Runs a blocking modal task that returns [T], suspend-friendly. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> runModal(
        project: Project,
        title: String,
        canBeCancelled: Boolean,
        block: (ProgressIndicator) -> T,
    ): T = withContext(Dispatchers.Default) {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            object : ThrowableComputable<T, Exception> {
                override fun compute(): T = block(ProgressManager.getInstance().progressIndicator)
            },
            title,
            canBeCancelled,
            project,
        )
    }

    /** Runs a non-blocking background task, resumes on EDT via onFinished. */
    suspend fun runBackground(
        project: Project,
        title: String,
        canBeCancelled: Boolean,
        /** Invoked when the user cancels the progress dialog, before the coroutine is cancelled. */
        onCancel: (() -> Unit)? = null,
        /** Invoked after the task has fully finished, including cancellation and failure paths. */
        onFinished: (() -> Unit)? = null,
        block: (ProgressIndicator) -> Unit,
    ) = runBackground(TaskRunner.DEFAULT, project, title, canBeCancelled, onCancel, onFinished, block)

    /** Internal entry-point with injectable [TaskRunner] for testing. Project is nullable for tests. */
    internal suspend fun runBackground(
        taskRunner: TaskRunner,
        project: Project?,
        title: String,
        canBeCancelled: Boolean,
        onCancel: (() -> Unit)?,
        onFinished: (() -> Unit)?,
        block: (ProgressIndicator) -> Unit,
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val cancelled = AtomicBoolean(false)
            val cancelCallbackInvoked = AtomicBoolean(false)
            val finishCallbackInvoked = AtomicBoolean(false)
            val continuationCompleted = AtomicBoolean(false)
            val indicatorRef = AtomicReference<ProgressIndicator>(null)
            val blockFailure = AtomicReference<Throwable>(null)

            fun invokeCancelCallback() {
                if (cancelCallbackInvoked.compareAndSet(false, true)) {
                    try { onCancel?.invoke() } catch (e: Exception) {
                        LOG.warn("onCancel callback threw", e)
                    }
                }
            }

            fun invokeFinishCallback() {
                if (finishCallbackInvoked.compareAndSet(false, true)) {
                    try { onFinished?.invoke() } catch (e: Exception) {
                        LOG.warn("onFinished callback threw", e)
                    }
                }
            }

            fun completeContinuation(result: Result<Unit>) {
                if (continuationCompleted.compareAndSet(false, true)) {
                    cont.resumeWith(result)
                }
            }

            fun cancelContinuation() {
                if (continuationCompleted.compareAndSet(false, true)) {
                    try {
                        cont.cancel()
                    } catch (_: Exception) {
                        // Parent cancellation may already have completed the continuation.
                    }
                }
            }

            cont.invokeOnCancellation {
                cancelled.set(true)
                invokeCancelCallback()
                indicatorRef.get()?.cancel()
            }

            try {
                taskRunner.run(
                    project = project,
                    title = title,
                    canBeCancelled = canBeCancelled,
                    onRun = { indicator ->
                        indicatorRef.set(indicator)
                        if (cancelled.get()) return@run
                        try {
                            block(indicator)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelled.set(true)
                            invokeCancelCallback()
                            indicator.cancel()
                        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                            cancelled.set(true)
                            invokeCancelCallback()
                            indicator.cancel()
                        } catch (e: Exception) {
                            blockFailure.compareAndSet(null, e)
                        }
                    },
                    onFinished = {
                        invokeFinishCallback()
                        if (cancelled.get()) {
                            cancelContinuation()
                        } else {
                            val failure = blockFailure.get()
                            completeContinuation(if (failure == null) Result.success(Unit) else Result.failure(failure))
                        }
                    },
                    onCancel = {
                        cancelled.set(true)
                        invokeCancelCallback()
                        indicatorRef.get()?.cancel()
                    },
                )
            } catch (e: Exception) {
                LOG.warn("TaskRunner.run threw synchronously", e)
                invokeFinishCallback()
                completeContinuation(Result.failure(e))
            }
        }
    }
}
