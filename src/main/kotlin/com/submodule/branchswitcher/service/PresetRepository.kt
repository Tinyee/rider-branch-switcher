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
    private val ensureFile: (Path) -> Path,
    private val saver: (Path, PresetFile) -> Unit,
) {

    constructor(project: Project) : this(
        basePath = { project.basePath?.let(java.nio.file.Paths::get) },
        loader = { PresetLoader.load(it) },
        ensureFile = PresetLoader::ensureFile,
        saver = PresetLoader::save,
    )

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null

    val presets: List<Preset> get() = presetFile.presets

    fun load(): Result<Pair<Path, PresetFile>> {
        val base = basePath()
            ?: return Result.failure(IllegalStateException("project base path is null"))
        return loader(base).onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
        }
    }

    fun save(newPresets: List<Preset>) {
        val file = savedFilePath ?: run {
            val base = basePath()
                ?: throw IllegalStateException("project base path is null — cannot save presets")
            ensureFile(base)
        }
        val updated = presetFile.copy(presets = newPresets)
        saver(file, updated)
        savedFilePath = file
        presetFile = updated
    }
}
