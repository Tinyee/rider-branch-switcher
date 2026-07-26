package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.isValidBranchName
import com.submodule.branchswitcher.model.isValidSubmodulePath
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.presetValidationError
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

    @Test
    fun `submodule paths must stay relative and normalized`() {
        listOf("SubA", "modules/nested", "folder with spaces/sub").forEach {
            assertTrue("Expected valid submodule path: $it", isValidSubmodulePath(it))
        }
        listOf("", ".", "..", "../outside", "a/../outside", "/tmp/repo", "C:/repo", "a\\b", "a//b").forEach {
            assertFalse("Expected invalid submodule path: $it", isValidSubmodulePath(it))
        }
    }

    @Test
    fun `preset validation checks every executable target`() {
        assertTrue(presetValidationError(Preset("dev", "main", mapOf("SubA" to "feature"))) == null)
        assertTrue(presetValidationError(Preset("dev", "main", mapOf("../outside" to "feature"))) != null)
        assertTrue(presetValidationError(Preset("dev", "-force")) != null)
        assertTrue(presetValidationError(Preset("dev", "main", mapOf("SubA" to ""))) != null)
    }
}
