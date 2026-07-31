package com.submodule.branchswitcher.service

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class PresetRepositoryTest {

    @Test
    fun `failed save keeps the last persisted snapshot in memory`() = runBlocking {
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

        val failedSave = runCatching { repository.save(listOf(Preset("dev", "dev"))) }
        assertTrue(failedSave.exceptionOrNull() is IOException)
        assertEquals(listOf(original), repository.presets)

        failSave = false
        val updated = Preset("release", "release")
        repository.save(listOf(updated))
        assertEquals(listOf(updated), repository.presets)
    }

    @Test
    fun `save is refused until the preset collection has loaded successfully`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.failure(IOException("temporarily unreadable")) },
            saver = { _, _ -> saveAttempts++ },
        )

        assertTrue(
            runCatching { repository.save(listOf(Preset("dev", "dev"))) }
                .exceptionOrNull() is IllegalStateException,
        )
        repository.load()
        assertTrue(
            runCatching { repository.save(listOf(Preset("dev", "dev"))) }
                .exceptionOrNull() is IllegalStateException,
        )
        assertEquals(0, saveAttempts)
    }

    @Test
    fun `failed reload blocks stale snapshot from overwriting the preset file`() = runBlocking {
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
        assertTrue(
            runCatching { repository.save(listOf(Preset("dev", "dev"))) }
                .exceptionOrNull() is IllegalStateException,
        )
        assertEquals(0, saveAttempts)
    }

    @Test
    fun `load waits for an active save before reading the file again`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val saveStarted = CompletableDeferred<Unit>()
        val allowSaveToFinish = CompletableDeferred<Unit>()
        val secondLoadStarted = CompletableDeferred<Unit>()
        var loadCount = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                loadCount++
                if (loadCount == 2) secondLoadStarted.complete(Unit)
                Result.success(file to PresetFile(listOf(Preset("main", "main"))))
            },
            saver = { _, _ ->
                saveStarted.complete(Unit)
                runBlocking { allowSaveToFinish.await() }
            },
        )
        repository.load().getOrThrow()

        val save = async { repository.save(listOf(Preset("dev", "dev"))) }
        saveStarted.await()
        val reload = async { repository.load() }

        assertFalse(
            "reload must not enter the loader while save owns the repository",
            withTimeoutOrNull(100) { secondLoadStarted.await() } != null,
        )
        allowSaveToFinish.complete(Unit)
        save.await()
        reload.await().getOrThrow()
        assertTrue(secondLoadStarted.isCompleted)
    }
}
