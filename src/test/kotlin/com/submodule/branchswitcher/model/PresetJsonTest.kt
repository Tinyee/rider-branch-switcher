package com.submodule.branchswitcher.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PresetJsonTest {

    private val gson = Gson()

    @Test
    fun `round-trip export import single preset`() {
        val original = PresetFile(listOf(Preset("dev", "dev", mapOf("SubA" to "dev"), pullEnabled = false)))
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, PresetFile::class.java)
        assertEquals(1, restored.presets.size)
        assertEquals("dev", restored.presets[0].name)
        assertEquals("dev", restored.presets[0].main)
        assertEquals(mapOf("SubA" to "dev"), restored.presets[0].submodules)
        assertFalse(restored.presets[0].pullEnabled)
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
        val original = Preset("old-name", "main", mapOf("SubA" to "dev"), pullEnabled = false)
        val renamed = original.copy(name = "new-name")
        assertEquals("new-name", renamed.name)
        assertEquals("main", renamed.main)
        assertEquals(mapOf("SubA" to "dev"), renamed.submodules)
        assertFalse(renamed.pullEnabled)
    }

    @Test
    fun `preset id survives JSON round-trip`() {
        val original = Preset("test", "main")
        val json = gson.toJson(PresetFile(listOf(original)))
        val restored = gson.fromJson(json, PresetFile::class.java)
        assertEquals(original.id, restored.presets[0].id)
    }

    @Test
    fun `old JSON without id auto generates one`() {
        val json = """{"presets":[{"name":"legacy","main":"main"}]}"""
        val dto = gson.fromJson(json, PresetFileDto::class.java)
        val restored = dto.toPresetFile()
        assertEquals("legacy", restored.presets[0].name)
        // Should have auto-generated a non-empty id
        val id = restored.presets[0].id
        assertTrue("Auto-generated id should not be blank", id.isNotBlank())
    }

    @Test
    fun `imported preset gets new id`() {
        val original = Preset("shared", "main")
        val imported = original.copy(id = java.util.UUID.randomUUID().toString())
        assertEquals("shared", imported.name)
        assertEquals("main", imported.main)
        // Imported copy should have a different id
        assertTrue(original.id != imported.id)
    }

    @Test
    fun `preset copy is immutable on original`() {
        val original = Preset("test", "main", mapOf("SubA" to "dev"), pullEnabled = true)
        val copied = original.copy(name = "renamed", pullEnabled = false)
        assertEquals("test", original.name)
        assertTrue(original.pullEnabled)
        assertEquals("renamed", copied.name)
        assertFalse(copied.pullEnabled)
    }
}
