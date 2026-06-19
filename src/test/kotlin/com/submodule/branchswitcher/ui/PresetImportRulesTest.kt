package com.submodule.branchswitcher.ui

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
    }

    @Test
    fun `blank input produces no import candidates`() {
        val result = parsePresetImport("   ", emptySet())

        assertTrue(result.presets.isEmpty())
        assertTrue(result.invalidNames.isEmpty())
        assertTrue(result.conflictingNames.isEmpty())
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
    fun `plain clipboard text produces no import candidates`() {
        val result = parsePresetImport("not preset json", emptySet())

        assertTrue(result.presets.isEmpty())
        assertTrue(result.invalidNames.isEmpty())
        assertTrue(result.conflictingNames.isEmpty())
    }

    @Test
    fun `json string clipboard text produces no import candidates`() {
        val result = parsePresetImport("\"not preset json\"", emptySet())

        assertTrue(result.presets.isEmpty())
        assertTrue(result.invalidNames.isEmpty())
        assertTrue(result.conflictingNames.isEmpty())
    }

    @Test
    fun `malformed json produces no import candidates`() {
        val result = parsePresetImport("{not json}", emptySet())

        assertTrue(result.presets.isEmpty())
        assertTrue(result.invalidNames.isEmpty())
        assertTrue(result.conflictingNames.isEmpty())
    }
}
