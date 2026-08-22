package com.submodule.branchswitcher.operation

import com.submodule.branchswitcher.git.GitOperationSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks one cancellable Git operation session so a cancellation requested before the
 * session was attached still reaches it. Shared by branch discovery and repository-state
 * refresh, which both attach a session only after cancellation may already be pending.
 *
 * This is the session-owned cancellation idiom. For workflow-level cancellation — the
 * `OperationControl` handle and the single `OperationCancelledException` type in the
 * `switch` package instead.
 */
class SessionCancelGuard {
    private val cancelled = AtomicBoolean(false)
    private val operation = AtomicReference<GitOperationSession?>()

    fun attach(candidate: GitOperationSession) {
        operation.set(candidate)
        if (cancelled.get()) candidate.cancel()
    }

    fun detach(candidate: GitOperationSession) {
        operation.compareAndSet(candidate, null)
    }

    fun cancel() {
        cancelled.set(true)
        operation.get()?.cancel()
    }

    fun isCancelled(): Boolean = cancelled.get()
}
