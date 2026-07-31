package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.operation.GitOperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class GitBackgroundRunnerTest {
    private val project = proxy<Project>()
    private val indicator = proxy<ProgressIndicator>()

    @Test
    fun `completed value survives task cancellation and session is cancelled then closed`() = runBlocking {
        val provider = RecordingProvider()
        val runner = GitBackgroundRunner(project, provider, taskRunner(cancelAfterRun = true))

        val result = runner.run("Switching") { progress, _ ->
            progress.isIndeterminate = true
            "completed"
        }

        assertEquals(GitOperationResult.Cancelled("completed"), result)
        assertEquals(1, provider.openCount)
        assertEquals(1, provider.cancelCount)
        assertEquals(1, provider.closeCount)
    }

    @Test
    fun `business exception becomes failed result and still closes session`() = runBlocking {
        val provider = RecordingProvider()
        val runner = GitBackgroundRunner(project, provider, taskRunner(cancelAfterRun = false))
        val failure = IllegalStateException("cannot inspect repository")

        val result = runner.run("Switching") { _, _ -> throw failure }

        val reported = (result as GitOperationResult.Failed).error
        assertEquals(IllegalStateException::class.java, reported.javaClass)
        assertEquals("cannot inspect repository", reported.message)
        assertEquals(1, provider.openCount)
        assertEquals(0, provider.cancelCount)
        assertEquals(1, provider.closeCount)
    }

    private fun taskRunner(cancelAfterRun: Boolean): TaskBridge.TaskRunner = object : TaskBridge.TaskRunner {
        override fun run(
            project: Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        ) {
            onRun(indicator)
            if (cancelAfterRun) onCancel()
            onFinished()
        }
    }

    private class RecordingProvider : GitOperationProvider {
        var openCount = 0
        var cancelCount = 0
        var closeCount = 0

        override fun openOperation(): GitOperationSession {
            openCount++
            return Proxy.newProxyInstance(
                GitOperationSession::class.java.classLoader,
                arrayOf(GitOperationSession::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> cancelCount++
                    "close" -> closeCount++
                }
                defaultValue(method.returnType)
            } as GitOperationSession
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> defaultValue(method.returnType) } as T

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
