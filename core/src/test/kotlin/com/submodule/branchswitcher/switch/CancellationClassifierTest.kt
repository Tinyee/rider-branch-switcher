package com.submodule.branchswitcher.switch

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationClassifierTest {

    private val classifier = CancellationClassifier.DEFAULT

    @Test
    fun `default classifier recognizes jdk cancellation`() {
        assertTrue(classifier.isCancellation(CancellationException()))
    }

    @Test
    fun `default classifier rejects genuine failures`() {
        assertFalse(classifier.isCancellation(IllegalStateException("boom")))
        assertFalse(classifier.isCancellation(RuntimeException()))
    }

    @Test
    fun `rethrowIfCancellation rethrows cancellation`() {
        val cancellation = CancellationException()
        val thrown = try {
            classifier.rethrowIfCancellation(cancellation)
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun `rethrowIfCancellation does not throw for non-cancellation`() {
        classifier.rethrowIfCancellation(IllegalStateException("boom"))
        // reaching here without an exception is the assertion
    }
}
