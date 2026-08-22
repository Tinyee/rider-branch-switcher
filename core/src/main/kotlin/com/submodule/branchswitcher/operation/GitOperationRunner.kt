package com.submodule.branchswitcher.operation

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.switch.OperationControl
import com.submodule.branchswitcher.switch.ProgressHandle

/** Progress and cancellation view exposed to platform-independent workflows. */
interface OperationProgress : OperationControl, ProgressHandle

/** Outcome of one isolated background Git operation. */
sealed class GitOperationResult<out T> {
    data class Completed<T>(val value: T) : GitOperationResult<T>()
    data class Cancelled<T>(val value: T? = null) : GitOperationResult<T>()
    data class Failed(val error: RuntimeException) : GitOperationResult<Nothing>()
}

/** Runs business work in an isolated Git session and can open a fresh recovery session. */
interface GitOperationRunner {
    suspend fun <T> run(
        title: String,
        block: (OperationProgress, GitOperationSession) -> T,
    ): GitOperationResult<T>

    fun openOperation(): GitOperationSession
}
