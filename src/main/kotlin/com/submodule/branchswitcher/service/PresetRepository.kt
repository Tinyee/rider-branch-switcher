package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
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
 */
class PresetRepository internal constructor(
    private val basePath: () -> Path?,
    private val loader: (Path) -> Result<Pair<Path, PresetFile>>,
    private val saver: (Path, PresetFile) -> Unit,
) {

    constructor(project: Project) : this(
        basePath = { project.basePath?.let(java.nio.file.Paths::get) },
        loader = PresetLoader::load,
        saver = PresetLoader::save,
    )

    private val access = Mutex()

    @Volatile
    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null
    private var synchronizedWithDisk = false

    val presets: List<Preset> get() = presetFile.presets

    suspend fun load(): Result<Pair<Path, PresetFile>> = access.withLock {
        synchronizedWithDisk = false
        val base = basePath()
            ?: return@withLock Result.failure(IllegalStateException("project base path is null"))
        withContext(Dispatchers.IO) { loader(base) }.onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
            synchronizedWithDisk = true
        }
    }

    suspend fun save(newPresets: List<Preset>) = access.withLock {
        check(synchronizedWithDisk) {
            "preset collection has not been loaded successfully; reload before saving"
        }
        val file = checkNotNull(savedFilePath) {
            "preset file path is unavailable after a successful load"
        }
        val updated = presetFile.copy(presets = newPresets)
        withContext(Dispatchers.IO) { saver(file, updated) }
        savedFilePath = file
        presetFile = updated
    }
}
