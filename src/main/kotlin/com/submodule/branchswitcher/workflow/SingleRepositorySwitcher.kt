package com.submodule.branchswitcher.workflow

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.platform.GitBackgroundResult
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.switch.resolveGitDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

enum class SingleRepositorySkipReason {
    NOT_INITIALIZED,
    DIRTY,
    ALREADY_ON_TARGET,
}

sealed class SingleRepositorySwitchResult {
    data class Skipped(val reason: SingleRepositorySkipReason) : SingleRepositorySwitchResult()
    data class Success(val result: GitResult) : SingleRepositorySwitchResult()
    data class GitFailure(val result: GitResult) : SingleRepositorySwitchResult()
    data object Cancelled : SingleRepositorySwitchResult()
    data class Unexpected(val error: Exception) : SingleRepositorySwitchResult()
}

/** Executes one repository checkout with the shared write and Git task lifecycle. */
class SingleRepositorySwitcher(
    private val project: Project,
    private val gitClient: () -> GitOperationProvider,
    private val tryAcquireWrite: () -> AutoCloseable?,
    private val taskRunner: TaskBridge.TaskRunner = TaskBridge.TaskRunner.DEFAULT,
) {
    /**
     * Acquires the write gate synchronously, then runs Git on [scope].
     * Returns false without launching when another write already owns the gate.
     */
    fun start(
        scope: CoroutineScope,
        root: Path,
        path: String,
        target: String,
        onResult: (SingleRepositorySwitchResult) -> Unit,
    ): Boolean {
        val writeLease = tryAcquireWrite() ?: return false
        val guardedLease = CloseOnce(writeLease)
        val job = scope.launch(Dispatchers.Default) {
            onResult(execute(root, path, target, guardedLease))
        }
        job.invokeOnCompletion { guardedLease.close() }
        return true
    }

    @Suppress("TooGenericExceptionCaught") // path, provider, and platform adapters share this result boundary
    private suspend fun execute(
        root: Path,
        path: String,
        target: String,
        writeLease: AutoCloseable,
    ): SingleRepositorySwitchResult {
        return try {
            val dir = resolveGitDir(root, path)
            when (val background = GitBackgroundRunner(project, gitClient(), taskRunner).run(
                Bundle.msg("progress.switching.to", target),
            ) { indicator, operation ->
                indicator.isIndeterminate = true
                when {
                    !dir.exists() || !operation.isGitRepo(dir) ->
                        SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_INITIALIZED)
                    operation.isDirty(dir) ->
                        SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.DIRTY)
                    operation.currentBranch(dir) == target ->
                        SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.ALREADY_ON_TARGET)
                    operation.localBranchExists(dir, target) ->
                        operation.checkoutExisting(dir, target).toSwitchResult()
                    operation.remoteBranchExists(dir, target) ->
                        operation.checkoutFromRemote(dir, target).toSwitchResult()
                    else -> SingleRepositorySwitchResult.GitFailure(
                        GitResult("checkout", 1, "", "branch $target not found"),
                    )
                }
            }) {
                is GitBackgroundResult.Completed -> background.value
                is GitBackgroundResult.Cancelled -> SingleRepositorySwitchResult.Cancelled
                is GitBackgroundResult.Failed -> SingleRepositorySwitchResult.Unexpected(background.error)
            }
        } catch (e: Exception) {
            if (platformCancellationClassifier.isCancellation(e)) {
                SingleRepositorySwitchResult.Cancelled
            } else {
                SingleRepositorySwitchResult.Unexpected(e)
            }
        } finally {
            writeLease.close()
        }
    }

    private fun GitResult.toSwitchResult(): SingleRepositorySwitchResult =
        if (ok) {
            SingleRepositorySwitchResult.Success(this)
        } else {
            SingleRepositorySwitchResult.GitFailure(this)
        }

    private class CloseOnce(
        private val delegate: AutoCloseable,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) delegate.close()
        }
    }
}
