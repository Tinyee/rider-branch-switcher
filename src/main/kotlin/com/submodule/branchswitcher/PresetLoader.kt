package com.submodule.branchswitcher

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.nio.file.Files
import java.nio.file.Path

object PresetLoader {
    const val FILE_NAME = ".branch-presets.json"
    const val IDEA_FILE_NAME = "branch-presets.json"

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

    fun save(file: Path, presetFile: PresetFile) {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        Files.writeString(file, gson.toJson(presetFile) + "\n")
    }

    private val DEFAULT_JSON = """
{
  "presets": [
    {
      "name": "using/develop",
      "main": "using/develop",
      "pull": true,
      "submodules": {
        "01_Project/Assets/MahjongGame/GameRes/Config~": "using/develop",
        "01_Project/Assets/MahjongGame/Script/Level": "level_mahjong_develop",
        "01_Project/Assets/XingyunFramework": "mahjong2_develop",
        "01_Project/Assets/ExperimentToolkit": "using/develop"
      }
    },
    {
      "name": "using/release",
      "main": "using/release",
      "pull": true,
      "submodules": {
        "01_Project/Assets/MahjongGame/GameRes/Config~": "using/release",
        "01_Project/Assets/MahjongGame/Script/Level": "level_mahjong_release",
        "01_Project/Assets/XingyunFramework": "mahjong2_release",
        "01_Project/Assets/ExperimentToolkit": "using/release"
      }
    },
    {
      "name": "using/release_week",
      "main": "using/release_week",
      "pull": true,
      "submodules": {
        "01_Project/Assets/MahjongGame/GameRes/Config~": "using/release_week",
        "01_Project/Assets/MahjongGame/Script/Level": "level_mahjong_master",
        "01_Project/Assets/XingyunFramework": "mahjong2_master",
        "01_Project/Assets/ExperimentToolkit": "using/release_week"
      }
    }
  ]
}
""".trimIndent()
}
