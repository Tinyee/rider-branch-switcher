package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProcessCanceledException
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCancellationClassifierTest {

    @Test
    fun `recognizes cancelled git query as cancellation`() {
        val cancelled = GitQueryException(GitResult("git status", -1, "", "cancelled"))

        assertTrue(platformCancellationClassifier.isCancellation(cancelled))
    }

    @Test
    fun `recognizes interrupted git query as cancellation`() {
        val interrupted = GitQueryException(GitResult("git status", -1, "", "interrupted"))

        assertTrue(platformCancellationClassifier.isCancellation(interrupted))
    }

    @Test
    fun `rejects genuine git failures`() {
        val failed = GitQueryException(GitResult("git status", 128, "", "fatal: not a git repository"))

        assertFalse(platformCancellationClassifier.isCancellation(failed))
    }

    @Test
    fun `recognizes intellij process canceled exception as cancellation`() {
        assertTrue(platformCancellationClassifier.isCancellation(ProcessCanceledException()))
    }
}
