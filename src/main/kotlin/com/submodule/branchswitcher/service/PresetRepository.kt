package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.config.PresetLoadResult
import com.submodule.branchswitcher.config.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cached preset file loader/saver.
 *
 * Save-time conflict detection is optimistic: it re-reads the file and compares
 * against the digest captured at load (which is derived from the exact bytes the
 * loader parsed). An external edit between that check and the atomic write is
 * still possible in the microseconds before the rename; full protection would
 * require file locking, which is disproportionate for a project-local JSON file.
 */
class PresetRepository internal constructor(
    private val basePath: () -> Path?,
    private val loader: (Path) -> Result<PresetLoadResult>,
    private val saver: (Path, PresetFile) -> ByteArray,
    private val digester: (Path) -> ByteArray? = PresetLoader::digest,
) {

    constructor(project: Project) : this(
        basePath = { project.basePath?.let(java.nio.file.Paths::get) },
        loader = PresetLoader::loadWithDigest,
        saver = PresetLoader::save,
    )

    private val access = Mutex()

    @Volatile
    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null
    private var synchronizedWithDisk = false
    @Volatile
    private var recordedDigest: ByteArray? = null

    /** Load saw invalid entries that were dropped; the next save must back up the original first. */
    private var dropBackupPending = false

    val presets: List<Preset> get() = presetFile.presets

    suspend fun load(): Result<PresetLoadOutcome> = access.withLock {
        synchronizedWithDisk = false
        recordedDigest = null
        dropBackupPending = false
        val base = basePath()
            ?: return@withLock Result.failure(IllegalStateException("project base path is null"))
        withContext(Dispatchers.IO) { loader(base) }.onSuccess { loaded ->
            savedFilePath = loaded.file
            presetFile = loaded.presetFile
            // The loader derives the digest from the same bytes it parsed, so the
            // recorded digest can never describe different content than presetFile.
            recordedDigest = loaded.digest
            dropBackupPending = loaded.droppedNames.isNotEmpty()
            if (loaded.droppedNames.isNotEmpty()) {
                LOG.warn(
                    "preset file $loaded.file: skipped ${loaded.droppedNames.size} invalid preset(s): " +
                        loaded.droppedNames.joinToString(", "),
                )
            }
            synchronizedWithDisk = true
        }.map { PresetLoadOutcome(it.file, it.presetFile, it.droppedNames) }
    }

    suspend fun save(newPresets: List<Preset>) = access.withLock {
        check(synchronizedWithDisk) {
            "preset collection has not been loaded successfully; reload before saving"
        }
        val file = checkNotNull(savedFilePath) {
            "preset file path is unavailable after a successful load"
        }
        val currentDigest = withContext(Dispatchers.IO) { digester(file) }
        if (digestChanged(currentDigest, recordedDigest)) throw PresetFileChangedException(file)
        // The load dropped invalid entries; the write below permanently removes them
        // from the file, so preserve the original bytes once as a recovery copy. If the
        // copy fails, refuse the overwrite: the backup is the only durable copy of the
        // dropped entries. dropBackupPending stays set so the next save re-attempts it.
        if (dropBackupPending) {
            val backedUp = withContext(Dispatchers.IO) { backupOriginal(file) }
            if (!backedUp) throw PresetBackupFailedException(file)
            dropBackupPending = false
        }
        val updated = presetFile.copy(presets = newPresets)
        // The saver returns the digest of the exact bytes it wrote, so memory and disk
        // stay consistent even if a re-read of the file would fail after the write.
        val writtenDigest = withContext(Dispatchers.IO) { saver(file, updated) }
        savedFilePath = file
        presetFile = updated
        recordedDigest = writtenDigest
    }

    private fun digestChanged(current: ByteArray?, recorded: ByteArray?): Boolean =
        if (current == null && recorded == null) false
        else if (current == null || recorded == null) true
        else !current.contentEquals(recorded)

    /**
     * Copies the pre-filtered file so entries dropped as invalid at load are recoverable
     * after the next save rewrites the file without them. Returns true when the original
     * is preserved (including the no-file case); false when the copy failed. The caller
     * must then refuse the destructive overwrite, because the backup is the only durable
     * copy of the dropped entries.
     */
    @Suppress("TooGenericExceptionCaught") // backup failure is a refusal reason; any IO failure is caught here
    private fun backupOriginal(file: Path): Boolean {
        if (!Files.exists(file)) return true
        val backup = file.resolveSibling(file.fileName.toString() + ".bak")
        return try {
            Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            LOG.warn("preset backup to $backup failed: ${e.message}", e)
            false
        }
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("SubmoduleBranchSwitcher")
    }
}

/** Successful preset load: the parsed collection plus any entries dropped as invalid. */
data class PresetLoadOutcome(
    val file: Path,
    val presets: PresetFile,
    val droppedNames: List<String>,
)
