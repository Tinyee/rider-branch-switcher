package com.submodule.branchswitcher.presentation

import org.junit.Assert.*
import org.junit.Test

class PresetImportRulesTest {

    @Test
    fun `imports array format and assigns new ids`() {
        var id = 0

        val result = parsePresetImport(
            """[{"id":"shared","name":"dev","main":"main"}]""",
            emptySet(),
        ) { "new-${++id}" }

        assertEquals(1, result.presets.size)
        assertEquals("new-1", result.presets.single().id)
        assertEquals("dev", result.presets.single().name)
        assertEquals("main", result.presets.single().main)
    }

    @Test
    fun `imports preset file format and preserves optional defaults`() {
        val result = parsePresetImport(
            """{"presets":[{"name":"dev","main":"main","submodules":{"SubA":"feature"}}]}""",
            emptySet(),
        ) { "new-id" }

        assertEquals(mapOf("SubA" to "feature"), result.presets.single().submodules)
    }

    @Test
    fun `skips invalid existing and duplicate names`() {
        val result = parsePresetImport(
            """{"presets":[
                {"name":"","main":"main"},
                {"name":"missing-main"},
                {"name":"existing","main":"main"},
                {"name":"new","main":"main"},
                {"name":"new","main":"dev"}
            ]}""",
            setOf("existing"),
        ) { "id" }

        assertEquals(listOf("new"), result.presets.map { it.name })
        assertEquals(listOf("", "missing-main"), result.invalidNames)
        assertEquals(listOf("existing", "new"), result.conflictingNames)
        assertTrue(result.hasRecognizedEntries)
    }

    @Test
    fun `import with null presets entry is skipped`() {
        val result = parsePresetImport(
            """{"presets":[null,{"name":"ok","main":"main"}]}""",
            emptySet(),
        ) { "id" }

        assertEquals(1, result.presets.size)
        assertEquals("ok", result.presets.single().name)
    }

    @Test
    fun `non-import clipboard content produces no candidates`() {
        listOf("   ", "not preset json", "\"not preset json\"", "{not json}").forEach { text ->
            val result = parsePresetImport(text, emptySet())
            assertTrue("input: $text", result.presets.isEmpty())
            assertTrue("input: $text", result.invalidNames.isEmpty())
            assertTrue("input: $text", result.conflictingNames.isEmpty())
            assertFalse("input: $text", result.hasRecognizedEntries)
        }
    }

    @Test
    fun `duplicate-only import is recognized but has no new presets`() {
        val result = parsePresetImport(
            """{"presets":[{"name":"dev","main":"main"}]}""",
            setOf("dev"),
        )

        assertTrue(result.presets.isEmpty())
        assertEquals(listOf("dev"), result.conflictingNames)
        assertTrue(result.hasRecognizedEntries)
    }

    @Test
    fun `unsafe paths and invalid branches are rejected during import`() {
        val result = parsePresetImport(
            """{"presets":[
                {"name":"escape","main":"main","submodules":{"../outside":"dev"}},
                {"name":"bad-branch","main":"-force"}
            ]}""",
            emptySet(),
        )

        assertTrue(result.presets.isEmpty())
        assertEquals(listOf("escape", "bad-branch"), result.invalidNames)
    }
}
