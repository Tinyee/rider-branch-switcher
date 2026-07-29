package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
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
        loader = { PresetLoader.load(it) },
        saver = PresetLoader::save,
    )

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null
    private var synchronizedWithDisk = false

    val presets: List<Preset> get() = presetFile.presets

    fun load(): Result<Pair<Path, PresetFile>> {
        synchronizedWithDisk = false
        val base = basePath()
            ?: return Result.failure(IllegalStateException("project base path is null"))
        return loader(base).onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
            synchronizedWithDisk = true
        }
    }

    fun save(newPresets: List<Preset>) {
        check(synchronizedWithDisk) {
            "preset collection has not been loaded successfully; reload before saving"
        }
        val file = checkNotNull(savedFilePath) {
            "preset file path is unavailable after a successful load"
        }
        val updated = presetFile.copy(presets = newPresets)
        saver(file, updated)
        savedFilePath = file
        presetFile = updated
    }
}
