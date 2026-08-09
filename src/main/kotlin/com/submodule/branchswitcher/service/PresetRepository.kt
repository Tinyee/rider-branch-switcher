package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.PresetLoadResult
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    val presets: List<Preset> get() = presetFile.presets

    suspend fun load(): Result<Pair<Path, PresetFile>> = access.withLock {
        synchronizedWithDisk = false
        recordedDigest = null
        val base = basePath()
            ?: return@withLock Result.failure(IllegalStateException("project base path is null"))
        withContext(Dispatchers.IO) { loader(base) }.onSuccess { loaded ->
            savedFilePath = loaded.file
            presetFile = loaded.presetFile
            // The loader derives the digest from the same bytes it parsed, so the
            // recorded digest can never describe different content than presetFile.
            recordedDigest = loaded.digest
            synchronizedWithDisk = true
        }.map { it.file to it.presetFile }
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
}
