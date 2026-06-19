package com.submodule.branchswitcher.switch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchNameRulesTest {

    @Test
    fun `branch name validation accepts valid git branch shorthands`() {
        listOf("a", "feature/test", "release-1.2", "user_name/topic", "feature.locked").forEach {
            assertTrue("Expected valid branch name: $it", isValidBranchName(it))
        }
    }

    @Test
    fun `branch name validation rejects invalid git branch shorthands`() {
        listOf(
            "", " ", "-feature", "/feature", "feature/", "feature//test", ".hidden",
            "feature/.hidden", "feature.", "feature.lock/test", "feature/test.lock",
            "feature.lock", "feature..test", "feature@{test", "feature test",
            "feature\u0001test", "feature\u007ftest",
        ).forEach {
            assertFalse("Expected invalid branch name: $it", isValidBranchName(it))
        }
    }
}
