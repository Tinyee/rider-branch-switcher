package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.git.RepositoryStateGitClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression: a probe cancelled mid-run surfaces as a [GitQueryException] with
 * failureKind CANCELLED (GitProcessRunner writes stderr "cancelled"), which the
 * platform classifier must treat as cancellation so it is not logged as a
 * "[detect] ... failed" noise line.
 */
class RepositoryStateDetectorCancellationTest {

    @Test
    fun `cancelled git query propagates as cancellation and is not logged as failure`() {
        val logs = mutableListOf<String>()
        val git = object : RepositoryStateGitClient, RepositoryStateBatchGitClient {
            override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
                throw GitQueryException(GitResult("git status", -1, "", "cancelled"))

            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun isDirty(workDir: File): Boolean = false
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val detector = RepositoryStateDetector(
            log = createStringAppender { logs += it },
            cancellationClassifier = platformCancellationClassifier,
        )
        val request = detector.begin(File(".").toPath(), listOf("."))

        assertThrows(GitQueryException::class.java) { detector.detect(request, git) }
        assertTrue("cancelled probe must not be logged as failure", logs.none { "[detect]" in it })
    }
}
