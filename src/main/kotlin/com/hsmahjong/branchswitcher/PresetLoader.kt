package com.hsmahjong.branchswitcher

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.nio.file.Files
import java.nio.file.Path

object PresetLoader {
    const val FILE_NAME = ".branch-presets.json"

    fun load(projectRoot: Path): Result<PresetFile> {
        val file = projectRoot.resolve(FILE_NAME)
        if (!Files.exists(file)) {
            return Result.failure(IllegalStateException("$FILE_NAME not found at $projectRoot"))
        }
        return try {
            val text = Files.readString(file)
            val parsed = Gson().fromJson(text, PresetFile::class.java) ?: PresetFile()
            Result.success(parsed)
        } catch (e: JsonSyntaxException) {
            Result.failure(IllegalStateException("$FILE_NAME parse error: ${e.message}", e))
        }
    }
}
