package com.submodule.branchswitcher

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.submodule.branchswitcher.model.PresetFile
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

        var cur: Path? = ideBase.parent
        while (cur != null) {
            val candidate = cur.resolve(FILE_NAME)
            if (Files.exists(candidate)) return candidate
            if (Files.exists(cur.resolve(".git"))) return null
            cur = cur.parent
        }
        return null
    }

    fun ensureFile(ideBase: Path): Path {
        resolveFile(ideBase)?.let { return it }
        val target = ideBase.resolve(".idea").resolve(IDEA_FILE_NAME)
        Files.createDirectories(target.parent)
        Files.writeString(target, DEFAULT_JSON)
        return target
    }

    fun load(ideBase: Path): Result<Pair<Path, PresetFile>> {
        val file = ensureFile(ideBase)
        return try {
            val text = Files.readString(file)
            val parsed = Gson().fromJson(text, PresetFile::class.java) ?: PresetFile()
            Result.success(file to parsed)
        } catch (e: JsonSyntaxException) {
            Result.failure(IllegalStateException("$file parse error: ${e.message}", e))
        }
    }

    /** Writes [presetFile] to [file] atomically (temp file + rename). */
    fun save(file: Path, presetFile: PresetFile) {
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
        } catch (e: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
            throw e
        }
    }

    private val DEFAULT_JSON = """
{
  "presets": []
}
""".trimIndent()
}
