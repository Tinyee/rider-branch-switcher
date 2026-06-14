package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.model.DirtyAction
import org.junit.Assert.*
import org.junit.Test

class PresetImportRulesTest {

    @Test
    fun `imports array format and assigns new ids`() {
        var id = 0

        val result = parsePresetImport(
            """[{"id":"shared","name":"dev","main":"main","pull":false}]""",
            emptySet(),
        ) { "new-${++id}" }

        assertEquals(1, result.presets.size)
        assertEquals("new-1", result.presets.single().id)
        assertEquals("dev", result.presets.single().name)
        assertFalse(result.presets.single().overrides?.pull ?: true)
    }

    @Test
    fun `imports preset file format and preserves optional defaults`() {
        val result = parsePresetImport(
            """{"presets":[{"name":"dev","main":"main","submodules":{"SubA":"feature"}}]}""",
            emptySet(),
        ) { "new-id" }

        assertEquals(mapOf("SubA" to "feature"), result.presets.single().submodules)
        assertTrue(result.presets.single().overrides?.pull ?: true)
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
    fun `imports overrides from JSON`() {
        val result = parsePresetImport(
            """[{"name":"hotfix","main":"hotfix","overrides":{"dirty":"Force","fetchFirst":false}}]""",
            emptySet(),
        ) { "id" }

        assertEquals(1, result.presets.size)
        val ov = result.presets.single().overrides
        assertNotNull(ov)
        assertEquals(DirtyAction.Force, ov!!.dirty)
        assertEquals(false, ov.fetchFirst)
        assertNull(ov.pull)
    }

    @Test
    fun `malformed dirty in import drops only dirty field`() {
        val result = parsePresetImport(
            """[{"name":"test","main":"dev","overrides":{"dirty":"Bogus","pull":false}}]""",
            emptySet(),
        ) { "id" }

        assertEquals(1, result.presets.size)
        val ov = result.presets.single().overrides
        assertNotNull(ov)
        assertNull(ov!!.dirty) // malformed → skipped
        assertEquals(false, ov.pull) // preserved
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
}
