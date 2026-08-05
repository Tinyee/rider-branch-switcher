package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.expectedSubmoduleGitDirectory
import com.submodule.branchswitcher.switch.isUnassociatedSubmoduleWorktree
import com.submodule.branchswitcher.switch.loadSubmoduleTopology
import com.submodule.branchswitcher.switch.resolveGitDir
import com.submodule.branchswitcher.switch.SubmoduleTopology
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Path

enum class SingleRepositorySkipReason {
    NOT_REGISTERED,
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

data class SingleRepositorySwitchOutcome(
    val operationId: String,
    val result: SingleRepositorySwitchResult,
)

/** Executes one repository checkout with the shared write and Git task lifecycle. */
class SingleRepositorySwitcher(
    private val operations: GitOperationRunner,
    private val tryAcquireWrite: () -> AutoCloseable?,
    private val log: AppLogger,
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
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
        title: String,
        onResult: (SingleRepositorySwitchOutcome) -> Unit,
    ): Boolean {
        val operationId = newOperationId("single-switch")
        val operationLog = log.withContext(operationId)
        val job = WriteOperationLauncher(scope, tryAcquireWrite).launch(
            onBusy = {
                operationLog.warn("operation rejected: another repository write is already running")
            },
        ) {
            onResult(
                SingleRepositorySwitchOutcome(
                    operationId = operationId,
                    result = execute(root, path, target, title, operationLog),
                ),
            )
        }
        return job != null
    }

    @Suppress("TooGenericExceptionCaught") // path, provider, and platform adapters share this result boundary
    private suspend fun execute(
        root: Path,
        path: String,
        target: String,
        title: String,
        operationLog: AppLogger,
    ): SingleRepositorySwitchResult {
        operationLog.activity(
            "operation started: root=${root.toAbsolutePath().normalize()}, path=$path, branch=$target",
        )
        val result = try {
            val dir = resolveGitDir(root, path)
            when (val background = operations.run(title) { indicator, operation ->
                indicator.isIndeterminate = true
                operationLog.logGitRuntime(operation, root.toFile(), cancellationClassifier)
                val topology = operation.loadSubmoduleTopology(root.toFile())
                when {
                    topology.isUnregistered(path) -> {
                        operationLog.warn("safety gate: path is not registered in the current .gitmodules graph")
                        SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_REGISTERED)
                    }
                    !dir.exists() || !operation.isGitRepo(dir) -> {
                        operationLog.warn("safety gate: repository is not initialized at ${dir.absolutePath}")
                        SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_INITIALIZED)
                    }
                    else -> switchInitializedRepository(
                        root,
                        path,
                        target,
                        dir,
                        topology,
                        operation,
                        operationLog,
                    )
                }
            }) {
                is GitOperationResult.Completed -> background.value
                is GitOperationResult.Cancelled -> SingleRepositorySwitchResult.Cancelled
                is GitOperationResult.Failed -> SingleRepositorySwitchResult.Unexpected(background.error)
            }
        } catch (e: Exception) {
            if (cancellationClassifier.isCancellation(e)) {
                SingleRepositorySwitchResult.Cancelled
            } else {
                SingleRepositorySwitchResult.Unexpected(e)
            }
        }
        when (result) {
            is SingleRepositorySwitchResult.Success ->
                operationLog.activity("operation finished: status=success, path=$path, branch=$target")
            is SingleRepositorySwitchResult.GitFailure ->
                operationLog.warn("operation finished: status=git-failure, ${result.result.diagnostic()}")
            is SingleRepositorySwitchResult.Skipped ->
                operationLog.warn("operation finished: status=skipped, reason=${result.reason}")
            SingleRepositorySwitchResult.Cancelled ->
                operationLog.info("operation finished: status=cancelled")
            is SingleRepositorySwitchResult.Unexpected ->
                operationLog.error("operation finished: status=unexpected-failure", result.error)
        }
        return result
    }

    private fun switchInitializedRepository(
        root: Path,
        path: String,
        target: String,
        directory: File,
        topology: SubmoduleTopology,
        operation: GitOperationSession,
        operationLog: AppLogger,
    ): SingleRepositorySwitchResult {
        val identity = operation.repositoryIdentity(directory)
        val expectedGitDirectory = expectedSubmoduleGitDirectory(root.toFile(), topology.byPath[path], operation)
        if (isUnassociatedSubmoduleWorktree(
                root.toFile(),
                path,
                directory,
                identity,
                expectedGitDirectory,
            )
        ) {
            operationLog.warn(
                "safety gate: repository is not associated with its superproject; " +
                    "actualGitDir=${identity?.gitDirectory}, expectedGitDir=$expectedGitDirectory, " +
                    "superproject=${identity?.superprojectRoot}",
            )
            return SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_REGISTERED)
        }
        return when {
            operation.isDirty(directory) ->
                SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.DIRTY)
            operation.currentBranch(directory) == target ->
                SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.ALREADY_ON_TARGET)
            operation.localBranchExists(directory, target) ->
                operation.checkoutExisting(directory, target).toSwitchResult()
            operation.remoteBranchExists(directory, target) ->
                operation.checkoutFromRemote(directory, target).toSwitchResult()
            else -> SingleRepositorySwitchResult.GitFailure(
                GitResult("checkout", 1, "", "branch $target not found"),
            )
        }
    }

    private fun GitResult.toSwitchResult(): SingleRepositorySwitchResult =
        if (ok) {
            SingleRepositorySwitchResult.Success(this)
        } else {
            SingleRepositorySwitchResult.GitFailure(this)
        }

}
