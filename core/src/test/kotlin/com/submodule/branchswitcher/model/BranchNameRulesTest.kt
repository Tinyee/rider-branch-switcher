package com.submodule.branchswitcher.model

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
        listOf("a", "feature/test", "release-1.2", "user_name/topic", "feature.locked", "@").forEach {
            assertTrue("Expected valid branch name: $it", isValidBranchName(it))
        }
    }

    @Test
    fun `branch name validation rejects invalid git branch shorthands`() {
        listOf(
            "", " ", "-feature", "/feature", "feature/", "feature//test", ".hidden",
            "feature/.hidden", "feature.", "feature.lock/test", "feature/test.lock",
            "feature.lock", "feature..test", "feature@{test", "feature test",
            "feature\u0001test", "feature\u007ftest", "HEAD",
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

    @Test
    fun `branch name validation agrees with git check-ref-format --branch`() {
        // Representative corpus covering every rule the validator encodes. Any mismatch is
        // silent product drift: a name git accepts but the plugin rejects (or vice versa)
        // would be dropped or accepted with a meaning git does not have.
        val corpus = listOf(
            "a", "feature/test", "release-1.2", "user_name/topic", "feature.locked", "@",
            "", " ", "-feature", "/feature", "feature/", "feature//test", ".hidden",
            "feature/.hidden", "feature.", "feature.lock/test", "feature/test.lock",
            "feature.lock", "feature..test", "feature@{test", "feature test",
            "feature\u0001test", "feature\u007ftest", "HEAD",
        )
        val mismatches = corpus.filter { name ->
            isValidBranchName(name) != gitAcceptsAsBranchShorthand(name)
        }
        assertTrue(
            "isValidBranchName disagrees with `git check-ref-format --branch` on: $mismatches",
            mismatches.isEmpty(),
        )
    }

    /** Verdict of the system git for `check-ref-format --branch`, the rule this validator models. */
    private fun gitAcceptsAsBranchShorthand(name: String): Boolean {
        val process = ProcessBuilder("git", "check-ref-format", "--branch", name)
            .redirectErrorStream(true)
            .start()
        process.inputStream.transferTo(java.io.OutputStream.nullOutputStream())
        process.waitFor()
        return process.exitValue() == 0
    }
}
