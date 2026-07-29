package com.submodule.branchswitcher.service

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class PresetRepositoryTest {

    @Test
    fun `failed save keeps the last persisted snapshot in memory`() {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val original = Preset("main", "main")
        var failSave = true
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(file to PresetFile(listOf(original))) },
            saver = { _, _ -> if (failSave) throw IOException("read only") },
        )
        repository.load().getOrThrow()

        assertThrows(IOException::class.java) {
            repository.save(listOf(Preset("dev", "dev")))
        }
        assertEquals(listOf(original), repository.presets)

        failSave = false
        val updated = Preset("release", "release")
        repository.save(listOf(updated))
        assertEquals(listOf(updated), repository.presets)
    }

    @Test
    fun `save is refused until the preset collection has loaded successfully`() {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.failure(IOException("temporarily unreadable")) },
            saver = { _, _ -> saveAttempts++ },
        )

        assertThrows(IllegalStateException::class.java) {
            repository.save(listOf(Preset("dev", "dev")))
        }
        repository.load()
        assertThrows(IllegalStateException::class.java) {
            repository.save(listOf(Preset("dev", "dev")))
        }
        assertEquals(0, saveAttempts)
    }

    @Test
    fun `failed reload blocks stale snapshot from overwriting the preset file`() {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val original = Preset("main", "main")
        var loadSucceeds = true
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                if (loadSucceeds) {
                    Result.success(file to PresetFile(listOf(original)))
                } else {
                    Result.failure(IOException("temporarily unreadable"))
                }
            },
            saver = { _, _ -> saveAttempts++ },
        )
        repository.load().getOrThrow()
        loadSucceeds = false

        repository.load()

        assertEquals(listOf(original), repository.presets)
        assertThrows(IllegalStateException::class.java) {
            repository.save(listOf(Preset("dev", "dev")))
        }
        assertEquals(0, saveAttempts)
    }
}
