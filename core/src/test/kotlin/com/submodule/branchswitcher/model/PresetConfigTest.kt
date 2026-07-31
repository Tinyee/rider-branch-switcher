package com.submodule.branchswitcher.model

import org.junit.Test
import org.junit.Assert.*

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

}
