package com.submodule.branchswitcher.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.submodule.branchswitcher.model.PresetFile
import com.submodule.branchswitcher.model.PresetFileDto
import com.submodule.branchswitcher.model.requireValidPreset
import java.nio.charset.StandardCharsets
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

    // Gson instances are thread-safe and immutable; reuse instead of rebuilding per call.
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

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
        if (isRepositoryBoundary(ideBase)) return null

        var currentDirectory: Path? = ideBase.parent
        while (currentDirectory != null) {
            val candidate = currentDirectory.resolve(FILE_NAME)
            if (Files.exists(candidate)) return candidate
            if (isRepositoryBoundary(currentDirectory)) return null
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
    fun load(ideBase: Path): Result<Pair<Path, PresetFile>> =
        loadWithDigest(ideBase).map { it.file to it.presetFile }

    /**
     * Like [load], but also returns the digest of the exact bytes that were parsed,
     * so conflict detection compares against the same content the caller saw.
     */
    fun loadWithDigest(ideBase: Path): Result<PresetLoadResult> {
        return runCatching {
            val file = resolveFile(ideBase) ?: defaultFile(ideBase)
            if (!Files.exists(file)) return@runCatching PresetLoadResult(file, PresetFile(), null)
            val bytes = Files.readAllBytes(file)
            val text = String(bytes, StandardCharsets.UTF_8)
            val dto = gson.fromJson(text, PresetFileDto::class.java) ?: PresetFileDto()
            val (parsed, _, droppedNames) = normalizePresetIds(dto)
            PresetLoadResult(
                file,
                parsed,
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
                droppedNames,
            )
        }.recoverCatching { e ->
            when (e) {
                is JsonSyntaxException -> throw IllegalStateException("preset file parse error: ${e.message}", e)
                else -> throw e
            }
        }
    }

    private fun normalizePresetIds(dto: PresetFileDto): Triple<PresetFile, Boolean, List<String>> {
        val usedIds = mutableSetOf<String>()
        var changed = false
        val dropped = mutableListOf<String>()
        val presets = dto.presets.orEmpty().filterNotNull().mapNotNull { presetDto ->
            val existingId = presetDto.id?.takeIf { it.isNotBlank() }
            val id = if (existingId != null && usedIds.add(existingId)) {
                existingId
            } else {
                changed = true
                generateUniqueId(usedIds)
            }
            runCatching { presetDto.toPreset(explicitId = id) }.getOrElse {
                // One invalid preset (blank branch name, unsafe path) must not make the
                // whole file unloadable: drop it and let the next save rewrite the file
                // without it. Same per-entry policy as parsePresetImport.
                dropped += presetDto.name?.trim()?.ifEmpty { id } ?: id
                changed = true
                null
            }
        }
        return Triple(PresetFile(presets), changed, dropped)
    }

    private fun generateUniqueId(usedIds: MutableSet<String>): String {
        while (true) {
            val id = java.util.UUID.randomUUID().toString()
            if (usedIds.add(id)) return id
        }
    }

    /**
     * SHA-256 digest of [file]'s raw bytes, or null when the file does not exist.
     *
     * Content-based rather than mtime/inode-based because [save] replaces the file
     * via an atomic rename, which changes the inode on every write.
     */
    fun digest(file: Path): ByteArray? =
        if (!Files.exists(file)) null
        else {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest()
        }

    /**
     * Writes [presetFile] to [file] atomically (temp file + rename) and returns the
     * SHA-256 of the exact bytes written, so the caller can record the new on-disk
     * digest without a second read (which could race an external edit).
     */
    fun save(file: Path, presetFile: PresetFile): ByteArray {
        presetFile.presets.forEach(::requireValidPreset)
        val payload = (prettyGson.toJson(presetFile) + "\n").toByteArray(StandardCharsets.UTF_8)
        val parent = file.parent ?: throw IllegalStateException("preset file has no parent: $file")
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, file.fileName.toString() + ".", ".tmp")
        try {
            Files.write(tmp, payload)
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
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload)
    }
}

internal fun isRepositoryBoundary(directory: Path): Boolean = Files.exists(directory.resolve(".git"))

/**
 * A successfully loaded preset file plus the digest of the exact bytes that were parsed.
 * [droppedNames] lists preset entries that were invalid and skipped so the file still
 * loads; callers surface them instead of treating the whole load as failed.
 */
data class PresetLoadResult(
    val file: Path,
    val presetFile: PresetFile,
    val digest: ByteArray?,
    val droppedNames: List<String> = emptyList(),
)
