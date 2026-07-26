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
            ensureFile = { file },
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
}
