package com.submodule.branchswitcher.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PresetJsonTest {

    private val gson = Gson()

    @Test
    fun `round-trip export import single preset`() {
        val original = PresetFile(listOf(Preset("dev", "dev", mapOf("SubA" to "dev"), pull = false)))
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, PresetFile::class.java)
        assertEquals(1, restored.presets.size)
        assertEquals("dev", restored.presets[0].name)
        assertEquals("dev", restored.presets[0].main)
        assertEquals(mapOf("SubA" to "dev"), restored.presets[0].submodules)
        assertFalse(restored.presets[0].pull)
    }

    @Test
    fun `round-trip multiple presets`() {
        val original = PresetFile(listOf(
            Preset("main", "main", mapOf("SubA" to "main", "SubB" to "main")),
            Preset("dev", "dev", mapOf("SubA" to "dev", "SubB" to "feature-x")),
        ))
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, PresetFile::class.java)
        assertEquals(2, restored.presets.size)
        assertEquals(2, restored.presets[0].submodules.size)
        assertEquals(2, restored.presets[1].submodules.size)
    }

    @Test
    fun `import empty json`() {
        val json = """{"presets":[]}"""
        val restored = gson.fromJson(json, PresetFile::class.java)
        assertTrue(restored.presets.isEmpty())
    }

    @Test
    fun `rename preset preserves other fields`() {
        val original = Preset("old-name", "main", mapOf("SubA" to "dev"), pull = false)
        val renamed = original.copy(name = "new-name")
        assertEquals("new-name", renamed.name)
        assertEquals("main", renamed.main)
        assertEquals(mapOf("SubA" to "dev"), renamed.submodules)
        assertFalse(renamed.pull)
    }

    @Test
    fun `preset copy is immutable on original`() {
        val original = Preset("test", "main", mapOf("SubA" to "dev"), pull = true)
        val copied = original.copy(name = "renamed", pull = false)
        assertEquals("test", original.name)
        assertTrue(original.pull)
        assertEquals("renamed", copied.name)
        assertFalse(copied.pull)
    }
}
