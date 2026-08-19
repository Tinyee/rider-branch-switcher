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
    fun `resolveFile finds root preset file when dot-idea missing`() {
        val rootFile = tmpDir.resolve(".branch-presets.json")
        Files.writeString(rootFile, """{"presets":[]}""")

        val found = PresetLoader.resolveFile(tmpDir)
        assertEquals(rootFile, found)
    }

    @Test
    fun `resolveFile finds ancestor preset beyond six parent directories`() {
        val ancestor = tmpDir.resolve(".branch-presets.json")
        Files.writeString(ancestor, """{"presets":[]}""")
        val deeplyNestedProject = (1..8).fold(tmpDir) { parent, index ->
            Files.createDirectories(parent.resolve("level-$index"))
        }

        val found = PresetLoader.resolveFile(deeplyNestedProject)

        assertEquals(ancestor, found)
    }

    @Test
    fun `resolveFile stops at git boundary when no file at that level`() {
        val repository = Files.createDirectories(tmpDir.resolve("repository"))
        val child = Files.createDirectories(repository.resolve("child"))
        Files.createDirectories(repository.resolve(".git"))
        Files.writeString(tmpDir.resolve(".branch-presets.json"), """{"presets":[]}""")

        val found = PresetLoader.resolveFile(child)

        assertNull(found)
    }

    @Test
    fun `resolveFile does not cross ide base when it is the git root`() {
        val repository = Files.createDirectories(tmpDir.resolve("repository"))
        Files.createDirectories(repository.resolve(".git"))
        Files.writeString(tmpDir.resolve(".branch-presets.json"), """{"presets":[]}""")

        val found = PresetLoader.resolveFile(repository)

        assertNull(found)
    }

    @Test
    fun `repository boundary accepts worktree git file`() {
        Files.writeString(tmpDir.resolve(".git"), "gitdir: ../metadata")

        assertTrue(isRepositoryBoundary(tmpDir))
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

    // ---- load ----

    @Test
    fun `load returns empty presets without creating a file`() {
        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        val (file, parsed) = result.getOrThrow()
        assertEquals(PresetLoader.defaultFile(tmpDir), file)
        assertTrue(parsed.presets.isEmpty())
        assertFalse(Files.exists(file))
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
        assertTrue(parsed.presets[0].submodules.isEmpty())
    }

    @Test
    fun `load replaces blank and duplicate ids with unique ids`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val file = ideaDir.resolve("branch-presets.json")
        val originalJson = """{"presets":[
                {"id":"","name":"blank","main":"main"},
                {"id":"shared","name":"first","main":"main"},
                {"id":"shared","name":"duplicate","main":"main"}
            ]}"""
        Files.writeString(file, originalJson)

        val presets = PresetLoader.load(tmpDir).getOrThrow().second.presets

        assertEquals(3, presets.map { it.id }.distinct().size)
        assertTrue(presets.none { it.id.isBlank() })
        assertEquals("shared", presets[1].id)
        assertNotEquals("shared", presets[2].id)
        assertEquals(originalJson, Files.readString(file))
    }

    @Test
    fun `load preserves valid unique ids without modifying JSON`() {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        val file = ideaDir.resolve("branch-presets.json")
        val originalJson = """{"presets":[{"id":"stable","name":"dev","main":"main"}]}"""
        Files.writeString(file, originalJson)

        val result = PresetLoader.load(tmpDir)

        assertTrue(result.isSuccess)
        assertEquals("stable", result.getOrThrow().second.presets.single().id)
        assertEquals(originalJson, Files.readString(file))
    }

    @Test
    fun `presets missing required fields are dropped instead of failing the file`() {
        writePresetFile(
            """{"presets":[{"main":"dev"},{"name":"dev"},{"name":"ok","main":"main"}]}"""
        )
        val result = PresetLoader.loadWithDigest(tmpDir)
        assertTrue(result.isSuccess)
        val loaded = result.getOrThrow()
        assertEquals("the valid preset still loads", 1, loaded.presetFile.presets.size)
        assertEquals("ok", loaded.presetFile.presets[0].name)
        assertEquals("both invalid entries are surfaced as dropped", 2, loaded.droppedNames.size)
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
            Preset("a", "main", mapOf("SubA" to "dev")),
            Preset("b", "dev", mapOf("SubA" to "main", "SubB" to "feature")),
        ))
        val file = PresetLoader.defaultFile(tmpDir)
        PresetLoader.save(file, original)

        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        val (_, restored) = result.getOrThrow()
        assertEquals(original, restored)
    }

    @Test
    fun `save overwrites existing file`() {
        val file = PresetLoader.defaultFile(tmpDir)
        PresetLoader.save(file, PresetFile(listOf(Preset("old", "main"))))

        PresetLoader.save(file, PresetFile(listOf(Preset("new", "dev"))))
        val content = Files.readString(file)
        assertTrue(content.contains(""""name": "new""""))
        assertFalse(content.contains(""""name": "old""""))
    }

    @Test
    fun `presets with null item in list loads without NPE`() {
        writePresetFile("""{"presets":[null,{"name":"ok","main":"main"}]}""")
        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().second.presets.size)
        assertEquals("ok", result.getOrThrow().second.presets[0].name)
    }

    @Test
    fun `load JSON with presets null key loads without NPE`() {
        writePresetFile("""{"presets":null}""")
        val result = PresetLoader.load(tmpDir)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().second.presets.isEmpty())
    }

    @Test
    fun `unsafe submodule paths are dropped instead of failing the file`() {
        writePresetFile(
            """{"presets":[{"name":"unsafe","main":"main","submodules":{"../outside":"dev"}},{"name":"safe","main":"main"}]}"""
        )

        val result = PresetLoader.loadWithDigest(tmpDir)

        assertTrue(result.isSuccess)
        assertEquals("the valid preset still loads", 1, result.getOrThrow().presetFile.presets.size)
        assertEquals("safe", result.getOrThrow().presetFile.presets[0].name)
        assertEquals("the unsafe entry is surfaced as dropped", "unsafe", result.getOrThrow().droppedNames.single())
    }

    private fun writePresetFile(json: String) {
        val ideaDir = Files.createDirectories(tmpDir.resolve(".idea"))
        Files.writeString(ideaDir.resolve("branch-presets.json"), json.trimIndent())
    }
}
