package com.submodule.branchswitcher.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the correlation-model invariants that [withContext] log prefixes depend on. */
class OperationContextTest {

    @Test
    fun `inPhase preserves the id and adds the phase`() {
        val context = newOperationContext("switch")

        val phased = context.inPhase("refresh")

        assertEquals("phase chaining must keep the correlation id stable", context.id, phased.id)
        assertEquals("refresh", phased.phase)
        assertNull("a fresh context has no phase", context.phase)
    }

    @Test
    fun `logLabel formats id alone and id-slash-phase`() {
        val context = newOperationContext("switch")

        assertEquals(context.id, context.logLabel)
        assertEquals("${context.id}/refresh", context.inPhase("refresh").logLabel)
    }

    @Test
    fun `newOperationContext generates a kind-prefixed unique id`() {
        val first = newOperationContext("switch")
        val second = newOperationContext("switch")

        assertTrue("id must carry the operation kind", first.id.startsWith("switch-"))
        assertNotEquals("ids must be unique per context", first.id, second.id)
    }
}
