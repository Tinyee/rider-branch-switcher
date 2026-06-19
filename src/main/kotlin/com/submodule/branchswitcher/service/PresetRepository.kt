package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path

/**
 * Cached preset file loader/saver.
 */
class PresetRepository(private val project: Project) {

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null

    val presets: List<Preset> get() = presetFile.presets

    fun load(): Result<Pair<Path, PresetFile>> {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) }
            ?: return Result.failure(IllegalStateException("project base path is null"))
        return PresetLoader.load(base).onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
        }
    }

    fun save(newPresets: List<Preset>) {
        val file = savedFilePath ?: run {
            val base = project.basePath?.let { java.nio.file.Paths.get(it) }
                ?: throw IllegalStateException("project base path is null — cannot save presets")
            val resolved = PresetLoader.ensureFile(base)
            savedFilePath = resolved
            resolved
        }
        presetFile = presetFile.copy(presets = newPresets)
        PresetLoader.save(file, presetFile)
    }
}
