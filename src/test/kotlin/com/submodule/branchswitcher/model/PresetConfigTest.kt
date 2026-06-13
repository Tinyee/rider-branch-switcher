package com.submodule.branchswitcher.model

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PresetConfigTest {

    @Test
    fun `preset targets includes main and submodules`() {
        val preset = Preset("test", "main", mapOf("SubA" to "dev", "SubB" to "feature"))
        val targets = preset.targets()
        assertEquals(3, targets.size)
        assertEquals(RepoTarget(".", "main"), targets[0])
        assertEquals(RepoTarget("SubA", "dev"), targets[1])
        assertEquals(RepoTarget("SubB", "feature"), targets[2])
    }

    @Test
    fun `preset targets with no submodules`() {
        val preset = Preset("solo", "master")
        val targets = preset.targets()
        assertEquals(1, targets.size)
        assertEquals(RepoTarget(".", "master"), targets[0])
    }

    @Test
    fun `preset id is preserved on copy`() {
        val original = Preset("test", "main")
        val renamed = original.copy(name = "renamed")
        // Stable ID must survive rename so undo history remains valid
        assertEquals(original.id, renamed.id)
        assertEquals("renamed", renamed.name)
        assertEquals("main", renamed.main)
    }
}
