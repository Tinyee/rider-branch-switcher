package com.submodule.branchswitcher

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [PresetLoader] file search, load, and save.
 * Uses real temp directories — no IntelliJ runtime needed.
 */
class PresetLoaderTest {

    private lateinit var tmpDir: Path

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("preset-loader-test-")
    }

    @After
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    // ---- resolveFile ----

    @Test
    fun `resolveFile returns null when no preset file exists anywhere`() {
        assertNull(PresetLoader.resolveFile(tmpDir))
    }

    @Test
    fun `resolveFile finds dot-idea preset file first`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val ideaFile = ideaDir.resolve("branch-presets.json")
        Files.writeString(ideaFile, """{"presets":[]}""")

        val found = PresetLoader.resolveFile(tmpDir)
        assertEquals(ideaFile, found)
    }

    @Test
    fun `resolveFile finds root preset file when dot-idea missing`() {
        val rootFile = tmpDir.resolve(".branch-presets.json")
        Files.writeString(rootFile, """{"presets":[]}""")

        val found = PresetLoader.resolveFile(tmpDir)
        assertEquals(rootFile, found)
    }

    @Test
    fun `resolveFile walks up to ancestor directory`() {
        val child = Files.createDirectories(tmpDir.resolve("child"))
        // Put file at tmpDir (one level above child, but below the .git barrier)
        val ancestor = tmpDir.resolve(".branch-presets.json")
        Files.writeString(ancestor, """{"presets":[]}""")

        val found = PresetLoader.resolveFile(child)
        assertEquals(ancestor, found)
    }

    @Test
    fun `resolveFile stops at git boundary when no file at that level`() {
        val child = Files.createDirectories(tmpDir.resolve("child"))
        Files.createDirectories(tmpDir.resolve(".git")) // git repo at parent
        // No .branch-presets.json at child or parent → .git stops upward walk

        val found = PresetLoader.resolveFile(child)
        assertNull(found)
    }

    @Test
    fun `resolveFile returns dot-idea over root when both exist`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val ideaFile = ideaDir.resolve("branch-presets.json")
        Files.writeString(ideaFile, """{"presets":[{"name":"idea"}]}""")
        Files.writeString(tmpDir.resolve(".branch-presets.json"), """{"presets":[{"name":"root"}]}""")

        val found = PresetLoader.resolveFile(tmpDir)
        assertEquals(ideaFile, found)
    }

    // ---- ensureFile ----

    @Test
    fun `ensureFile creates new file when none exists`() {
        val file = PresetLoader.ensureFile(tmpDir)
        assertTrue(Files.exists(file))
        // ensureFile uses IDEA_FILE_NAME = "branch-presets.json"
        assertEquals("branch-presets.json", file.fileName.toString())
        val content = Files.readString(file).trim()
        assertTrue(content.contains(""""presets": []"""))
    }

    @Test
    fun `ensureFile returns existing file`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val existing = ideaDir.resolve("branch-presets.json")
        Files.writeString(existing, """{"presets":[{"name":"test","main":"dev"}]}""")

        val file = PresetLoader.ensureFile(tmpDir)
        assertEquals(existing, file)
    }

    // ---- load ----

    @Test
    fun `load creates and reads empty presets`() {
        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        val (file, parsed) = result.getOrThrow()
        assertNotNull(file)
        assertTrue(parsed.presets.isEmpty())
    }

    @Test
    fun `load reads existing presets`() {
        val id = tmpDir.resolve(".idea")
        Files.createDirectories(id)
        Files.writeString(id.resolve("branch-presets.json"), """
            {"presets":[{"name":"dev","main":"dev","pull":true}]}
        """.trimIndent())

        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        val (_, parsed) = result.getOrThrow()
        assertEquals(1, parsed.presets.size)
        assertEquals("dev", parsed.presets[0].name)
        assertEquals("dev", parsed.presets[0].main)
        assertTrue(parsed.presets[0].pullEnabled)
    }

    @Test
    fun `load applies defaults for optional preset fields`() {
        val id = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(id.resolve("branch-presets.json"), """
            {"presets":[{"name":"dev","main":"dev"}]}
        """.trimIndent())

        val result = PresetLoader.load(tmpDir)

        assertTrue(result.isSuccess)
        val preset = result.getOrThrow().second.presets.single()
        assertTrue(preset.submodules.isEmpty())
        assertTrue(preset.pullEnabled)
    }

    @Test
    fun `load writes generated id back to legacy JSON`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val file = ideaDir.resolve("branch-presets.json")
        Files.writeString(file, """{"presets":[{"name":"legacy","main":"main"}]}""")

        val result = PresetLoader.load(tmpDir)

        assertTrue(result.isSuccess)
        val generatedId = result.getOrThrow().second.presets.single().id
        assertTrue(Files.readString(file).contains(generatedId))
    }

    @Test
    fun `migration save failure does not prevent loading legacy JSON`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(
            ideaDir.resolve("branch-presets.json"),
            """{"presets":[{"name":"legacy","main":"main"}]}""",
        )

        val result = PresetLoader.load(tmpDir) { _, _ ->
            throw java.io.IOException("read only")
        }

        assertTrue(result.isSuccess)
        assertEquals("legacy", result.getOrThrow().second.presets.single().name)
    }

    @Test
    fun `load replaces blank and duplicate ids with unique ids`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(
            ideaDir.resolve("branch-presets.json"),
            """{"presets":[
                {"id":"","name":"blank","main":"main"},
                {"id":"shared","name":"first","main":"main"},
                {"id":"shared","name":"duplicate","main":"main"}
            ]}""",
        )

        val presets = PresetLoader.load(tmpDir).getOrThrow().second.presets

        assertEquals(3, presets.map { it.id }.distinct().size)
        assertTrue(presets.none { it.id.isBlank() })
        assertEquals("shared", presets[1].id)
        assertNotEquals("shared", presets[2].id)
    }

    @Test
    fun `load writes normalized ids back to JSON`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val file = ideaDir.resolve("branch-presets.json")
        Files.writeString(
            file,
            """{"presets":[
                {"id":"same","name":"a","main":"main"},
                {"id":"same","name":"b","main":"main"}
            ]}""",
        )

        val presets = PresetLoader.load(tmpDir).getOrThrow().second.presets
        val persisted = Files.readString(file)

        assertTrue(persisted.contains(presets[0].id))
        assertTrue(persisted.contains(presets[1].id))
        assertNotEquals(presets[0].id, presets[1].id)
    }

    @Test
    fun `load does not save when all ids are already valid and unique`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(
            ideaDir.resolve("branch-presets.json"),
            """{"presets":[{"id":"stable","name":"dev","main":"main"}]}""",
        )
        var saveCalls = 0

        val result = PresetLoader.load(tmpDir) { _, _ -> saveCalls++ }

        assertTrue(result.isSuccess)
        assertEquals(0, saveCalls)
        assertEquals("stable", result.getOrThrow().second.presets.single().id)
    }

    @Test
    fun `load rejects preset missing required name`() {
        val id = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(id.resolve("branch-presets.json"), """
            {"presets":[{"main":"dev"}]}
        """.trimIndent())

        val result = PresetLoader.load(tmpDir)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("preset.name"))
    }

    @Test
    fun `load rejects preset missing required main branch`() {
        val id = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(id.resolve("branch-presets.json"), """
            {"presets":[{"name":"dev"}]}
        """.trimIndent())

        val result = PresetLoader.load(tmpDir)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("preset.main"))
    }

    @Test
    fun `load returns failure on corrupted JSON`() {
        val id = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(id.resolve("branch-presets.json"), "{not json}")

        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("parse error"))
    }

    // ---- save + load round-trip ----

    @Test
    fun `save and load round-trip preserves data`() {
        val original = PresetFile(listOf(
            Preset("a", "main", mapOf("SubA" to "dev"), pullEnabled = false),
            Preset("b", "dev", mapOf("SubA" to "main", "SubB" to "feature"), pullEnabled = true),
        ))
        val file = PresetLoader.ensureFile(tmpDir)
        PresetLoader.save(file, original)

        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        val (_, restored) = result.getOrThrow()
        assertEquals(2, restored.presets.size)
        assertEquals("a", restored.presets[0].name)
        assertEquals(mapOf("SubA" to "dev"), restored.presets[0].submodules)
        assertFalse(restored.presets[0].pullEnabled)
        assertEquals("b", restored.presets[1].name)
        assertEquals(2, restored.presets[1].submodules.size)
        assertTrue(restored.presets[1].pullEnabled)
    }

    @Test
    fun `save creates parent directory if missing`() {
        val file = tmpDir.resolve("nested").resolve("dir").resolve("test.json")
        val presets = PresetFile(listOf(Preset("x", "main")))

        PresetLoader.save(file, presets)
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue("content should contain name:x, got: $content", content.contains(""""name": "x""""))
    }

    @Test
    fun `save overwrites existing file`() {
        val file = PresetLoader.ensureFile(tmpDir)
        Files.writeString(file, "old content")

        PresetLoader.save(file, PresetFile(listOf(Preset("new", "dev"))))
        val content = Files.readString(file)
        assertTrue(content.contains(""""name": "new""""))
        assertFalse(content.contains("old content"))
    }
}
