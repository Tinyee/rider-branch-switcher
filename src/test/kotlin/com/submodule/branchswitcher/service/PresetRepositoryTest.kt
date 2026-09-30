package com.submodule.branchswitcher.service

import com.submodule.branchswitcher.config.PresetLoadResult
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class PresetRepositoryTest {

    /**
     * Test-only equivalent of the removed `save()`: persists against the repository's
     * last-recorded digest. Lets existing scenarios exercise [saveWithBaseline] without
     * each having to thread the baseline through.
     */
    private suspend fun PresetRepository.save(newPresets: List<Preset>): ByteArray? =
        saveWithBaseline(newPresets, recordedDigestForTest)

    @Test
    fun `failed save keeps the last persisted snapshot in memory`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val original = Preset("main", "main")
        var failSave = true
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(listOf(original)), null)) },
            saver = { _, _ -> if (failSave) throw IOException("read only") else byteArrayOf() },
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
            saver = { _, _ -> saveAttempts++; byteArrayOf() },
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
                    Result.success(PresetLoadResult(file, PresetFile(listOf(original)), null))
                } else {
                    Result.failure(IOException("temporarily unreadable"))
                }
            },
            saver = { _, _ -> saveAttempts++; byteArrayOf() },
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
                Result.success(PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), null))
            },
            saver = { _, _ ->
                saveStarted.complete(Unit)
                runBlocking { allowSaveToFinish.await() }
                byteArrayOf()
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

    @Test
    fun `save refuses to overwrite when the file changed on disk since load`() = runBlocking {
        val original = Preset("main", "main")
        val harness = digestHarness(tempFile, listOf(original))
        harness.repository.load().getOrThrow()

        harness.digest = byteArrayOf(2) // external edit after load
        val exception = runCatching { harness.repository.save(listOf(Preset("dev", "dev"))) }.exceptionOrNull()
        assertTrue(exception is PresetFileChangedException)
        assertEquals(0, harness.saveAttempts)
        assertEquals(listOf(original), harness.repository.presets)
    }

    @Test
    fun `save proceeds when the on-disk digest is unchanged`() = runBlocking {
        val harness = digestHarness(tempFile, listOf(Preset("main", "main")))
        harness.repository.load().getOrThrow()

        val updated = Preset("dev", "dev")
        harness.repository.save(listOf(updated))
        assertEquals(1, harness.saveAttempts)
        assertEquals(listOf(updated), harness.repository.presets)
    }

    @Test
    fun `save detects a file created after load`() = runBlocking {
        val harness = digestHarness(tempFile, listOf(Preset("main", "main")), initialDigest = null)
        harness.repository.load().getOrThrow() // recordedDigest is null; file did not exist

        harness.digest = byteArrayOf(1) // file created externally after load
        val exception = runCatching { harness.repository.save(listOf(Preset("dev", "dev"))) }.exceptionOrNull()
        assertTrue(exception is PresetFileChangedException)
        assertEquals(0, harness.saveAttempts)
    }

    @Test
    fun `recorded digest refreshes after a successful save`() = runBlocking {
        val harness = digestHarness(tempFile, listOf(Preset("main", "main")))
        harness.writtenDigest = byteArrayOf(99) // the saver reports the bytes it wrote
        harness.repository.load().getOrThrow()

        val updated = Preset("dev", "dev")
        harness.repository.save(listOf(updated)) // current [1] == recorded [1]; recorded refreshes to [99]
        assertEquals(listOf(updated), harness.repository.presets)

        harness.digest = byteArrayOf(100) // external edit after our save
        val exception = runCatching { harness.repository.save(listOf(Preset("release", "release"))) }.exceptionOrNull()
        assertTrue(exception is PresetFileChangedException)
    }

    @Test
    fun `load records the digest of the bytes the loader parsed, not a separate re-read`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        // The loader parses bytes with digest [1]; a separate re-read of the file would
        // report [2]. Load must record the loader's digest, so a save after the file
        // changed is detected as a conflict.
        val loaderDigest = byteArrayOf(1)
        val onDiskDigest = byteArrayOf(2)
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), loaderDigest)) },
            saver = { _, _ -> saveAttempts++; byteArrayOf() },
            digester = { onDiskDigest },
        )
        repository.load().getOrThrow()

        val exception = runCatching { repository.save(listOf(Preset("dev", "dev"))) }.exceptionOrNull()
        assertTrue(exception is PresetFileChangedException)
        assertEquals(0, saveAttempts)
    }

    @Test
    fun `load surfaces dropped preset names to the caller`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                Result.success(
                    PresetLoadResult(
                        file,
                        PresetFile(listOf(Preset("main", "main"))),
                        null,
                        droppedNames = listOf("bad A", "bad B"),
                    )
                )
            },
            saver = { _, _ -> byteArrayOf() },
        )

        val outcome = repository.load().getOrThrow()

        assertEquals(listOf("bad A", "bad B"), outcome.droppedNames)
        assertEquals(file, outcome.file)
        // Preset carries a random id, so compare by name rather than whole-value equality.
        assertEquals(listOf("main"), outcome.presets.presets.map { it.name })
    }

    @Test
    fun `first save after a drop-load writes a backup of the exact on-disk bytes once`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        Files.createDirectories(file.parent)
        val onDiskBytes = """{"presets":[{"name":"bad"}]}""".toByteArray()
        Files.write(file, onDiskBytes)
        val diskDigest = sha256(onDiskBytes)
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                Result.success(
                    PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), diskDigest, droppedNames = listOf("bad"))
                )
            },
            // Real-ish saver: writes new bytes and reports their digest, so a second save
            // sees a consistent on-disk digest instead of a spurious conflict.
            saver = { path, _ ->
                saveAttempts++
                val written = """{"presets":[]}""".toByteArray()
                Files.write(path, written)
                sha256(written)
            },
            digester = { path -> if (Files.exists(path)) sha256(Files.readAllBytes(path)) else null },
        )
        repository.load().getOrThrow()
        val backup = file.resolveSibling("branch-presets.json.bak")
        assertFalse(Files.exists(backup))

        repository.save(listOf(Preset("dev", "dev")))

        assertTrue(Files.exists(backup))
        assertArrayEquals(onDiskBytes, Files.readAllBytes(backup))
        assertEquals(1, saveAttempts)
        // A later save must not overwrite the recovery copy again.
        repository.save(listOf(Preset("release", "release")))
        assertArrayEquals(onDiskBytes, Files.readAllBytes(backup))
    }

    @Test
    fun `save refused when the post-drop backup fails, keeping the original bytes`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        Files.createDirectories(file.parent)
        val onDiskBytes = """{"presets":[{"name":"bad"}]}""".toByteArray()
        Files.write(file, onDiskBytes)
        val diskDigest = sha256(onDiskBytes)
        var saveAttempts = 0
        // A non-empty directory squatting on the .bak path forces the backup copy to fail
        // (Files.copy throws DirectoryNotEmptyException; an empty directory would be replaced).
        val backupDir = root.resolve(".idea/branch-presets.json.bak")
        Files.createDirectories(backupDir)
        Files.write(backupDir.resolve("keep"), byteArrayOf())
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                Result.success(
                    PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), diskDigest, droppedNames = listOf("bad"))
                )
            },
            saver = { path, _ ->
                saveAttempts++
                val written = """{"presets":[]}""".toByteArray()
                Files.write(path, written)
                sha256(written)
            },
            digester = { path -> if (Files.exists(path)) sha256(Files.readAllBytes(path)) else null },
        )
        repository.load().getOrThrow()

        val exception = checkNotNull(
            runCatching { repository.save(listOf(Preset("dev", "dev"))) }.exceptionOrNull(),
        ) as PresetBackupFailedException

        assertEquals(file, exception.source)
        assertEquals(backupDir, exception.backup)
        assertTrue(
            "the underlying copy failure must be preserved",
            exception.cause is java.nio.file.DirectoryNotEmptyException,
        )
        assertEquals("the original file must not be overwritten when its backup failed", 0, saveAttempts)
        assertArrayEquals(onDiskBytes, Files.readAllBytes(file))
        // The refusal leaves dropBackupPending set, so once the obstruction is gone the
        // next save re-attempts the backup and proceeds.
        Files.delete(backupDir.resolve("keep"))
        Files.delete(backupDir)
        repository.save(listOf(Preset("release", "release")))
        assertEquals(1, saveAttempts)
        assertArrayEquals(onDiskBytes, Files.readAllBytes(root.resolve(".idea/branch-presets.json.bak")))
    }

    @Test
    fun `save after a clean load does not write a backup`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        Files.createDirectories(file.parent)
        val onDiskBytes = """{"presets":[]}""".toByteArray()
        Files.write(file, onDiskBytes)
        val diskDigest = sha256(onDiskBytes)
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), diskDigest)) },
            saver = { _, _ -> byteArrayOf() },
            digester = { diskDigest },
        )
        repository.load().getOrThrow()

        repository.save(listOf(Preset("dev", "dev")))

        assertFalse(Files.exists(file.resolveSibling("branch-presets.json.bak")))
    }

    @Test
    fun `save refused on digest conflict does not write a backup`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        Files.createDirectories(file.parent)
        Files.write(file, """{"presets":[]}""".toByteArray())
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                Result.success(
                    PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), byteArrayOf(1), droppedNames = listOf("bad"))
                )
            },
            saver = { _, _ -> byteArrayOf() },
            digester = { byteArrayOf(2) },
        )
        repository.load().getOrThrow()

        val exception = runCatching { repository.save(listOf(Preset("dev", "dev"))) }.exceptionOrNull()

        assertTrue(exception is PresetFileChangedException)
        assertFalse(Files.exists(file.resolveSibling("branch-presets.json.bak")))
    }

    @Test
    fun `saveWithBaseline refuses when the file moved off the baseline even after a newer load`() = runBlocking {
        // The MAJOR-1 regression: the tool window builds its editor list at digest D1, then
        // the preset shortcut loads the shared repository (recording D2) without the window
        // reloading. A window save must still be validated against the D1 baseline the list
        // was built from — otherwise the stale list silently overwrites the D2 bytes.
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val original = Preset("main", "main")
        val d1 = byteArrayOf(1)
        val d2 = byteArrayOf(2)
        var onDisk = d1
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = {
                // Simulate the disk now holding D2 (external edit or a reload that saw it).
                Result.success(PresetLoadResult(file, PresetFile(listOf(Preset("external", "dev"))), onDisk))
            },
            saver = { _, _ -> saveAttempts++; byteArrayOf() },
            digester = { onDisk },
        )
        // The window loaded when the disk was D1...
        repository.load().getOrThrow()
        val windowBaseline = d1
        // ...then an external edit (or the shortcut reload) moved the disk to D2 and the
        // shared repository recorded D2.
        onDisk = d2
        repository.load().getOrThrow()

        // The window saves its stale D1-based list: must be refused, not silently written.
        val exception = runCatching {
            repository.saveWithBaseline(listOf(original), windowBaseline)
        }.exceptionOrNull()
        assertTrue("stale-baseline save must be refused", exception is PresetFileChangedException)
        assertEquals(0, saveAttempts)
    }

    @Test
    fun `saveWithBaseline proceeds when the file still matches the baseline`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val original = Preset("main", "main")
        val d1 = byteArrayOf(1)
        var saveAttempts = 0
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(listOf(original)), d1)) },
            saver = { _, _ -> saveAttempts++; byteArrayOf() },
            digester = { d1 },
        )
        repository.load().getOrThrow()

        repository.saveWithBaseline(listOf(Preset("dev", "dev")), d1)

        assertEquals(1, saveAttempts)
        // The write advanced the recorded digest, so a follow-up plain save still sees a
        // consistent on-disk state. Preset carries a random id, so compare by name.
        assertEquals(listOf("dev"), repository.presets.map { it.name })
    }

    @Test
    fun `a second save succeeds when it adopts the written digest as its new baseline`() = runBlocking {
        // Regression for the consecutive-save false conflict: the window saves against D1,
        // the write advances the disk to D2, and the window must re-baseline to D2 (the
        // returned digest) rather than keep validating against D1.
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        val d1 = byteArrayOf(1)
        val d2 = byteArrayOf(2)
        var onDisk = d1
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(listOf(Preset("main", "main"))), onDisk)) },
            saver = { _, _ -> d2.also { onDisk = it } },
            digester = { onDisk },
        )
        repository.load().getOrThrow()

        val written = repository.saveWithBaseline(listOf(Preset("a", "a")), d1)
        // The second save must use the digest this window's own write produced.
        repository.saveWithBaseline(listOf(Preset("b", "b")), written)

        assertEquals(listOf("b"), repository.presets.map { it.name })
    }

    @Test
    fun `saveWithBaseline with a null baseline refuses once the file exists`() = runBlocking {
        // Window A's list was built from a load that saw no preset file (null baseline).
        // Window B (its own repository over the same file) then creates the file; window A's
        // save must be refused rather than silently overwrite it — the null baseline only
        // matches a still-absent file.
        val root = Files.createTempDirectory("preset-repository")
        val file = root.resolve(".idea/branch-presets.json")
        var onDisk: ByteArray? = null
        fun repository() = PresetRepository(
            basePath = { root },
            loader = { Result.success(PresetLoadResult(file, PresetFile(), onDisk)) },
            saver = { _, _ -> byteArrayOf(9).also { onDisk = it } },
            digester = { onDisk },
        )
        val windowA = repository()
        val windowB = repository()
        windowA.load().getOrThrow() // window A builds its list while the file is absent
        windowB.load().getOrThrow()

        // Window B saves first, creating the file and advancing the on-disk digest.
        windowB.saveWithBaseline(listOf(Preset("b", "b")), null)

        val exception = runCatching {
            windowA.saveWithBaseline(listOf(Preset("stale", "stale")), null)
        }.exceptionOrNull()

        assertTrue("a null baseline must refuse once the file exists", exception is PresetFileChangedException)
    }

    @Test
    fun `saveWithBaseline still requires a successful load`() = runBlocking {
        val root = Files.createTempDirectory("preset-repository")
        val repository = PresetRepository(
            basePath = { root },
            loader = { Result.failure(IOException("unreadable")) },
            saver = { _, _ -> byteArrayOf() },
        )

        assertTrue(
            runCatching { repository.saveWithBaseline(listOf(Preset("dev", "dev")), byteArrayOf(1)) }
                .exceptionOrNull() is IllegalStateException,
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    /** A fresh temp preset file path for one test. */
    private val tempFile: Path
        get() = Files.createTempDirectory("preset-repository").resolve(".idea/branch-presets.json")

    /**
     * Repository backed by a mutable digest shared by the loader and the digester, so a
     * test can simulate an external edit by flipping [DigestHarness.digest] between load
     * and save.
     */
    private fun digestHarness(
        file: Path,
        initialPresets: List<Preset>,
        initialDigest: ByteArray? = byteArrayOf(1),
    ) = DigestHarness(file, initialPresets, initialDigest)

    private class DigestHarness(
        file: Path,
        initialPresets: List<Preset>,
        initialDigest: ByteArray?,
    ) {
        var digest: ByteArray? = initialDigest
        var saveAttempts = 0
        var writtenDigest: ByteArray = byteArrayOf()
        val repository = PresetRepository(
            basePath = { file.parent?.parent },
            loader = { Result.success(PresetLoadResult(file, PresetFile(initialPresets), digest)) },
            saver = { _, _ -> saveAttempts++; writtenDigest },
            digester = { digest },
        )
    }
}
