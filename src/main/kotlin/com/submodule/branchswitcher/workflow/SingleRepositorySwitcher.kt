package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.expectedSubmoduleGitDirectory
import com.submodule.branchswitcher.switch.findBlockingIndexLocks
import com.submodule.branchswitcher.switch.indexLockBlockedDiagnostic
import com.submodule.branchswitcher.switch.unassociatedSubmoduleBlockReason
import com.submodule.branchswitcher.switch.loadSubmoduleTopology
import com.submodule.branchswitcher.switch.resolveGitDir
import com.submodule.branchswitcher.switch.SubmoduleTopology
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Path

enum class SingleRepositorySkipReason {
    /** The path is not a registered submodule of the current .gitmodules graph. */
    NOT_REGISTERED,
    /** The path is registered but its git directory is not the superproject's (moved/misplaced/unassociated). */
    UNASSOCIATED,
    NOT_INITIALIZED,
    DIRTY,
    ALREADY_ON_TARGET,
}

sealed class SingleRepositorySwitchResult {
    data class Skipped(val reason: SingleRepositorySkipReason) : SingleRepositorySwitchResult()
    data class Success(val result: GitResult) : SingleRepositorySwitchResult()
    data class GitFailure(val result: GitResult) : SingleRepositorySwitchResult()
    data class LockBlocked(val lockPath: String) : SingleRepositorySwitchResult()
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
            afterRelease = onResult,
        ) {
            SingleRepositorySwitchOutcome(
                operationId = operationId,
                result = execute(root, path, target, title, operationLog),
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
            is SingleRepositorySwitchResult.LockBlocked ->
                operationLog.warn(
                    "operation finished: status=lock-blocked, ${indexLockBlockedDiagnostic(result.lockPath)}",
                )
            is SingleRepositorySwitchResult.Skipped ->
                operationLog.warn("operation finished: status=skipped, reason=${result.reason}")
            SingleRepositorySwitchResult.Cancelled ->
                operationLog.info("operation finished: status=cancelled")
            is SingleRepositorySwitchResult.Unexpected ->
                operationLog.logFailure("operation finished: status=unexpected-failure", result.error)
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
        val blockReason = unassociatedSubmoduleBlockReason(
            root.toFile(),
            path,
            directory,
            identity,
            expectedGitDirectory,
            operationLog,
        )
        if (blockReason != null) {
            operationLog.warn("safety gate: $blockReason")
            return SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.UNASSOCIATED)
        }
        if (operation.isDirty(directory)) {
            return SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.DIRTY)
        }
        if (operation.currentBranch(directory) == target) {
            return SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.ALREADY_ON_TARGET)
        }
        blockingIndexLock(root, path, operation, operationLog)?.let { return it }
        return when {
            operation.localBranchExists(directory, target) ->
                switchWithLockGuard(root, path, operation, operationLog) {
                    operation.checkoutExisting(directory, target)
                }
            operation.remoteBranchExists(directory, target) ->
                switchWithLockGuard(root, path, operation, operationLog) {
                    operation.checkoutFromRemote(directory, target)
                }
            else -> {
                operationLog.warn("branch \"$target\" not found in $path (local and remote both missing)")
                SingleRepositorySwitchResult.GitFailure(
                    GitResult("checkout", 1, "", "branch $target not found"),
                )
            }
        }
    }

    /** Returns [SingleRepositorySwitchResult.LockBlocked] when a stale index.lock already blocks writes. */
    private fun blockingIndexLock(
        root: Path,
        path: String,
        operation: GitOperationSession,
        operationLog: AppLogger,
    ): SingleRepositorySwitchResult? {
        val block = findBlockingIndexLocks(root, operation, listOf(path)).firstOrNull() ?: return null
        operationLog.error(
            "stale index.lock blocks checkout of $path - delete it and retry: ${block.lockPath}",
        )
        return SingleRepositorySwitchResult.LockBlocked(block.lockPath)
    }

    /**
     * Re-checks the index lock immediately before the checkout write, closing the
     * gap between the earlier gate and this command (local/remote existence probes
     * run in between and may take milliseconds).
     */
    private fun switchWithLockGuard(
        root: Path,
        path: String,
        operation: GitOperationSession,
        operationLog: AppLogger,
        action: () -> GitResult,
    ): SingleRepositorySwitchResult {
        blockingIndexLock(root, path, operation, operationLog)?.let { return it }
        return action().toSwitchResult()
    }

    private fun GitResult.toSwitchResult(): SingleRepositorySwitchResult =
        if (ok) {
            SingleRepositorySwitchResult.Success(this)
        } else {
            SingleRepositorySwitchResult.GitFailure(this)
        }

}
