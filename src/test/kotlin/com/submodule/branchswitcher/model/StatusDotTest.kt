package com.submodule.branchswitcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusDotTest {

    /** Mirror of the status dot color logic in PresetEditor.applyCurrentState */
    enum class DotStatus { GREEN, RED, GRAY }

    private fun dotStatus(currentBranch: String?, targetBranch: String): DotStatus = when {
        currentBranch == null -> DotStatus.GRAY
        currentBranch == targetBranch -> DotStatus.GREEN
        else -> DotStatus.RED
    }

    @Test
    fun `null current means not initialized — gray`() {
        assertEquals(DotStatus.GRAY, dotStatus(null, "main"))
    }

    @Test
    fun `matching branch — green`() {
        assertEquals(DotStatus.GREEN, dotStatus("dev", "dev"))
        assertEquals(DotStatus.GREEN, dotStatus("main", "main"))
        assertEquals(DotStatus.GREEN, dotStatus("feature/x", "feature/x"))
    }

    @Test
    fun `mismatched branch — red`() {
        assertEquals(DotStatus.RED, dotStatus("main", "dev"))
        assertEquals(DotStatus.RED, dotStatus("feature-x", "main"))
    }

    @Test
    fun `all submodules matched — preset matches`() {
        val current = mapOf("." to "main", "SubA" to "dev", "SubB" to "feature-x")
        val preset = Preset("test", "main", mapOf("SubA" to "dev", "SubB" to "feature-x"))
        // Main matches
        assertEquals(current["."], preset.main)
        // Submodules all match
        preset.submodules.all { (path, branch) -> current[path] == branch }
        assertEquals(DotStatus.GREEN, dotStatus(current["SubA"], preset.submodules["SubA"]!!))
        assertEquals(DotStatus.GREEN, dotStatus(current["SubB"], preset.submodules["SubB"]!!))
    }

    @Test
    fun `one submodule mismatched — red dot`() {
        val current = mapOf("SubA" to "main")
        val target = "dev"
        assertEquals(DotStatus.RED, dotStatus(current["SubA"], target))
    }

    @Test
    fun `uninitialized submodule — gray dot`() {
        val current: Map<String, String?> = mapOf("SubA" to null)
        val target = "dev"
        assertEquals(DotStatus.GRAY, dotStatus(current["SubA"], target))
    }
}
