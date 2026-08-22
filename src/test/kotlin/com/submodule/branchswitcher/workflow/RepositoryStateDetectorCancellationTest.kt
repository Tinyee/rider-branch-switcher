package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.git.RepositoryStateGitClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.switch.OperationCancelledException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression: a probe cancelled mid-run surfaces as an [OperationCancelledException]
 * (the git read boundary converts failureKind CANCELLED into it), which must be treated
 * as cancellation so it is not logged as a "[detect] ... failed" noise line.
 */
class RepositoryStateDetectorCancellationTest {

    @Test
    fun `cancelled git query propagates as cancellation and is not logged as failure`() {
        val logs = mutableListOf<String>()
        val git = object : RepositoryStateGitClient, RepositoryStateBatchGitClient {
            override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
                throw OperationCancelledException("git status cancelled")

            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun isDirty(workDir: File): Boolean = false
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val detector = RepositoryStateDetector(
            log = createStringAppender { logs += it },
        )
        val request = detector.begin(File(".").toPath(), listOf("."))

        assertThrows(OperationCancelledException::class.java) { detector.detect(request, git) }
        assertTrue("cancelled probe must not be logged as failure", logs.none { "[detect]" in it })
    }
}
