package com.submodule.branchswitcher.ui

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetDto
import com.submodule.branchswitcher.model.PresetFileDto

data class PresetImportResult(
    val presets: List<Preset>,
    val invalidNames: List<String>,
    val conflictingNames: List<String>,
) {
    val hasRecognizedEntries: Boolean
        get() = presets.isNotEmpty() || invalidNames.isNotEmpty() || conflictingNames.isNotEmpty()
}

/**
 * Parses clipboard JSON and applies import rules without touching Swing or the system clipboard.
 * Imported presets always receive a new ID so shared files cannot collide with local history.
 */
fun parsePresetImport(
    text: String,
    existingNames: Set<String>,
    idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
): PresetImportResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return PresetImportResult(emptyList(), emptyList(), emptyList())
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return PresetImportResult(emptyList(), emptyList(), emptyList())
    }

    val gson = Gson()
    val dto = try {
        if (trimmed.startsWith("[")) {
            val presets = gson.fromJson(trimmed, Array<PresetDto>::class.java)
            PresetFileDto(presets?.toList().orEmpty())
        } else {
            gson.fromJson(trimmed, PresetFileDto::class.java) ?: PresetFileDto()
        }
    } catch (_: JsonParseException) {
        return PresetImportResult(emptyList(), emptyList(), emptyList())
    } catch (_: IllegalStateException) {
        return PresetImportResult(emptyList(), emptyList(), emptyList())
    }

    val acceptedNames = existingNames.toMutableSet()
    val presets = mutableListOf<Preset>()
    val invalid = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    for (presetDto in dto.presets.orEmpty()) {
        if (presetDto == null) continue
        val name = presetDto.name?.trim()
        val main = presetDto.main?.trim()
        if (name.isNullOrEmpty() || main.isNullOrEmpty()) {
            invalid += name ?: "(unnamed)"
            continue
        }
        if (!acceptedNames.add(name)) {
            conflicts += name
            continue
        }
        presets += presetDto.toPreset(explicitId = idGenerator())
    }
    return PresetImportResult(presets, invalid, conflicts)
}
