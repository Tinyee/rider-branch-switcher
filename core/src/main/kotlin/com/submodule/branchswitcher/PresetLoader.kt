package com.submodule.branchswitcher

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.submodule.branchswitcher.model.PresetFile
import com.submodule.branchswitcher.model.PresetFileDto
import com.submodule.branchswitcher.model.requireValidPreset
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes preset JSON files.
 *
 * Search order for [resolveFile]:
 * 1. `.idea/branch-presets.json` (IDE project config directory)
 * 2. `.branch-presets.json` (project root)
 * 3. Walk up parent directories until a `.git` dir or another `.branch-presets.json` is found
 *
 * Writes use an atomic pattern: write to a temp file, then rename (ATOMIC_MOVE)
 * with a non-atomic fallback when the filesystem doesn't support atomic moves.
 */
object PresetLoader {
    const val FILE_NAME = ".branch-presets.json"
    const val IDEA_FILE_NAME = "branch-presets.json"

    /**
     * Locates an existing preset file. Returns null if none found.
     * Starts at [ideBase], then walks upward until `.git` boundary.
     */
    fun resolveFile(ideBase: Path): Path? {
        val direct = listOf(
            ideBase.resolve(".idea").resolve(IDEA_FILE_NAME),
            ideBase.resolve(FILE_NAME),
        )
        for (c in direct) if (Files.exists(c)) return c

        var currentDirectory: Path? = ideBase.parent
        while (currentDirectory != null) {
            val candidate = currentDirectory.resolve(FILE_NAME)
            if (Files.exists(candidate)) return candidate
            if (Files.exists(currentDirectory.resolve(".git"))) return null
            currentDirectory = currentDirectory.parent
        }
        return null
    }

    /** Default location used by the first explicit save when no preset file exists. */
    fun defaultFile(ideBase: Path): Path =
        ideBase.resolve(".idea").resolve(IDEA_FILE_NAME)

    /**
     * Loads presets without modifying the filesystem.
     *
     * Missing files produce an empty in-memory collection whose path points at
     * [defaultFile]. Legacy IDs are normalized in memory and persist on the
     * next explicit save.
     */
    fun load(
        ideBase: Path,
    ): Result<Pair<Path, PresetFile>> {
        return runCatching {
            val file = resolveFile(ideBase) ?: defaultFile(ideBase)
            if (!Files.exists(file)) return@runCatching file to PresetFile()
            val text = Files.readString(file)
            val dto = Gson().fromJson(text, PresetFileDto::class.java) ?: PresetFileDto()
            val (parsed, _) = normalizePresetIds(dto)
            file to parsed
        }.recoverCatching { e ->
            when (e) {
                is JsonSyntaxException -> throw IllegalStateException("preset file parse error: ${e.message}", e)
                else -> throw e
            }
        }
    }

    private fun normalizePresetIds(dto: PresetFileDto): Pair<PresetFile, Boolean> {
        val usedIds = mutableSetOf<String>()
        var changed = false
        val presets = dto.presets.orEmpty().filterNotNull().map { presetDto ->
            val existingId = presetDto.id?.takeIf { it.isNotBlank() }
            val id = if (existingId != null && usedIds.add(existingId)) {
                existingId
            } else {
                changed = true
                generateUniqueId(usedIds)
            }
            presetDto.toPreset(explicitId = id)
        }
        return PresetFile(presets) to changed
    }

    private fun generateUniqueId(usedIds: MutableSet<String>): String {
        while (true) {
            val id = java.util.UUID.randomUUID().toString()
            if (usedIds.add(id)) return id
        }
    }

    /** Writes [presetFile] to [file] atomically (temp file + rename). */
    fun save(file: Path, presetFile: PresetFile) {
        presetFile.presets.forEach(::requireValidPreset)
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val payload = gson.toJson(presetFile) + "\n"
        val parent = file.parent ?: throw IllegalStateException("preset file has no parent: $file")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, file.fileName.toString() + ".", ".tmp")
        try {
            Files.writeString(tmp, payload)
            try {
                Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
                // The replacement already completed; a leftover temp file is harmless.
            }
        }
    }
}
