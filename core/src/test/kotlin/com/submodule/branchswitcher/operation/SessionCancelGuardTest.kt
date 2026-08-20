package com.submodule.branchswitcher.operation

import com.submodule.branchswitcher.git.GitOperationSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Locks the late-attach semantics of [SessionCancelGuard] that branch discovery and
 * repository-state refresh both rely on: a cancellation requested before the session
 * is attached must still reach it, and a detached session must not be cancelled.
 */
class SessionCancelGuardTest {

    @Test
    fun `cancel before attach still cancels the late session`() {
        val cancelled = AtomicBoolean(false)
        val guard = SessionCancelGuard()

        guard.cancel()
        assertTrue("cancel must be recorded before any session exists", guard.isCancelled())

        val session = fakeSession(cancelled)
        guard.attach(session)

        assertTrue("a session attached after cancel must be cancelled immediately", cancelled.get())
    }

    @Test
    fun `cancel after attach cancels the attached session`() {
        val cancelled = AtomicBoolean(false)
        val guard = SessionCancelGuard()
        val session = fakeSession(cancelled)

        guard.attach(session)
        assertFalse("no cancellation before cancel()", cancelled.get())

        guard.cancel()

        assertTrue("the attached session must be cancelled", cancelled.get())
        assertTrue(guard.isCancelled())
    }

    @Test
    fun `detach prevents cancellation of the detached session`() {
        val cancelled = AtomicBoolean(false)
        val guard = SessionCancelGuard()
        val session = fakeSession(cancelled)

        guard.attach(session)
        guard.detach(session)
        guard.cancel()

        assertFalse("a detached session must not be cancelled", cancelled.get())
    }

    @Test
    fun `detach only clears the matching session`() {
        val firstCancelled = AtomicBoolean(false)
        val secondCancelled = AtomicBoolean(false)
        val guard = SessionCancelGuard()

        guard.attach(fakeSession(firstCancelled))
        val second = fakeSession(secondCancelled)
        guard.attach(second)

        // detach of the superseded first session must not clear the current one.
        guard.detach(fakeSession(firstCancelled))
        guard.cancel()

        assertFalse("superseded session must not be cancelled", firstCancelled.get())
        assertTrue("current session must be cancelled", secondCancelled.get())
    }

    /** Proxy Git session routing `cancel` to [onCancel]; other methods return defaults. */
    private fun fakeSession(onCancel: AtomicBoolean): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> onCancel.set(true)
                "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as GitOperationSession
}
