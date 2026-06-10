package com.submodule.branchswitcher

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * Bridges IntelliJ's Task API to coroutines, enabling unified `scope.launch { }` usage.
 * Zero call sites use Task.Modal or Task.Backgroundable directly after this migration.
 */
object TaskBridge {

    /** Runs a blocking modal task that returns [T], suspend-friendly. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> runModal(
        project: Project,
        title: String,
        canBeCancelled: Boolean,
        block: (ProgressIndicator) -> T,
    ): T = withContext(Dispatchers.Default) {
        val box = arrayOfNulls<Any?>(1)
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            object : ThrowableComputable<Unit, Exception> {
                override fun compute(): Unit {
                    box[0] = block(ProgressManager.getInstance().progressIndicator)
                }
            },
            title,
            canBeCancelled,
            project,
        )
        box[0] as T
    }

    /** Runs a non-blocking background task, resumes on EDT via onFinished. */
    suspend fun runBackground(
        project: Project,
        title: String,
        canBeCancelled: Boolean,
        block: (ProgressIndicator) -> Unit,
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            val indicatorRef = java.util.concurrent.atomic.AtomicReference<ProgressIndicator>(null)
            val task = object : Task.Backgroundable(project, title, canBeCancelled) {
                override fun run(indicator: ProgressIndicator) {
                    indicatorRef.set(indicator)
                    try {
                        block(indicator)
                    } catch (e: Exception) {
                        cont.resumeWith(Result.failure(e))
                        return
                    }
                }
                override fun onFinished() {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                override fun onCancel() {
                    cont.cancel()
                }
            }
            cont.invokeOnCancellation {
                indicatorRef.get()?.cancel()
            }
            ProgressManager.getInstance().run(task)
        }
    }
}
