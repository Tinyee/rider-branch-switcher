package com.submodule.branchswitcher.switch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the locale-neutral notification mapping of stale-index.lock issues. */
class IndexLockPresentationTest {

    private fun lockIssue(repositoryPath: String?, lockPath: String?) = OperationIssue(
        stage = OperationStage.CHECKPOINT,
        code = OperationIssueCode.INDEX_LOCK_BLOCKING,
        repositoryPath = repositoryPath,
        lockPath = lockPath,
    )

    private fun otherIssue() = OperationIssue(
        stage = OperationStage.CHECKPOINT,
        code = OperationIssueCode.CHECKPOINT_UNAVAILABLE,
    )

    @Test
    fun `keeps only index lock issues and maps the main repo to its label`() {
        val presentations = lockBlockedPresentations(
            listOf(
                lockIssue(".", "/main/.git/index.lock"),
                lockIssue("SubA", "/main/.git/modules/SubA/index.lock"),
                lockIssue(null, null),
                otherIssue(),
            ),
            mainRepositoryLabel = "Main Repo",
        )

        assertEquals(
            listOf(
                LockBlockedPresentation("Main Repo", "/main/.git/index.lock"),
                LockBlockedPresentation("SubA", "/main/.git/modules/SubA/index.lock"),
                LockBlockedPresentation("Main Repo", ""),
            ),
            presentations,
        )
    }

    @Test
    fun `no index lock issues yield no lines`() {
        assertTrue(lockBlockedPresentations(listOf(otherIssue()), "Main Repo").isEmpty())
        assertTrue(lockBlockedPresentations(emptyList(), "Main Repo").isEmpty())
    }
}
