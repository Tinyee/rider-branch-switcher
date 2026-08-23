package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.operation.OperationProgress
import com.submodule.branchswitcher.switch.OperationCancelledException
import java.util.concurrent.CancellationException

internal enum class TestOperationCompletion {
    COMPLETE,
    CANCEL_BEFORE,
    CANCEL_AFTER,
}

internal class TestOperationProgress(
    override var isCanceled: Boolean = false,
    private val onCheckCancelled: () -> Unit = {},
) : OperationProgress {
    override fun checkCancelled() = onCheckCancelled()
    override var fraction: Double = 0.0
    override var text: String? = null
    override var text2: String? = null
    override var isIndeterminate: Boolean = false
}

/** Deterministic operation boundary for workflow tests; platform lifecycle has separate tests. */
internal class TestGitOperationRunner(
    private val provider: GitOperationProvider,
    private val completion: TestOperationCompletion = TestOperationCompletion.COMPLETE,
    private val progress: OperationProgress = TestOperationProgress(),
    /** Per-call completion override (indexed from 0), for multi-operation workflows where one run must behave differently. */
    private val completionForRun: (Int) -> TestOperationCompletion = { completion },
) : GitOperationRunner {
    private var runCount = 0

    override suspend fun <T> run(
        title: String,
        block: (OperationProgress, GitOperationSession) -> T,
    ): GitOperationResult<T> {
        val thisCompletion = completionForRun(runCount++)
        val operation = try {
            provider.openOperation()
        } catch (_: CancellationException) {
            return GitOperationResult.Cancelled()
        } catch (_: OperationCancelledException) {
            return GitOperationResult.Cancelled()
        } catch (e: RuntimeException) {
            return GitOperationResult.Failed(e)
        }
        return try {
            if (thisCompletion == TestOperationCompletion.CANCEL_BEFORE) {
                operation.cancel()
                GitOperationResult.Cancelled()
            } else {
                val value = block(progress, operation)
                if (thisCompletion == TestOperationCompletion.CANCEL_AFTER) {
                    operation.cancel()
                    GitOperationResult.Cancelled(value)
                } else {
                    GitOperationResult.Completed(value)
                }
            }
        } catch (_: CancellationException) {
            operation.cancel()
            GitOperationResult.Cancelled()
        } catch (_: OperationCancelledException) {
            operation.cancel()
            GitOperationResult.Cancelled()
        } catch (e: RuntimeException) {
            GitOperationResult.Failed(e)
        } finally {
            operation.close()
        }
    }

    override fun openOperation(): GitOperationSession = provider.openOperation()
}
