package com.submodule.branchswitcher.model

/** A single repository target: its path (or "." for main) and desired branch. */
data class RepoTarget(
    val path: String,
    val branch: String,
)

/**
 * A named branch preset — the central data model.
 * [targets()] returns main first (".") followed by submodules in order,
 * which is the required processing order for the switch pipeline.
 */
data class Preset(
    val name: String,
    val main: String,
    val submodules: Map<String, String> = emptyMap(),
    @com.google.gson.annotations.SerializedName("pull")
    val pullEnabled: Boolean = true,
) {
    /** Returns all targets: main (".") first, then submodules. Main-first ordering is critical for submodule init. */
    fun targets(): List<RepoTarget> {
        val list = mutableListOf(RepoTarget(".", main))
        (submodules ?: emptyMap()).forEach { (path, branch) -> list += RepoTarget(path, branch) }
        return list
    }
}

/**
 * Gson-safe DTO for [Preset]. All fields are nullable so Gson doesn't silently
 * set Kotlin defaults to JVM zero-values (null/false) via UnsafeAllocator.
 * Always convert to [Preset] via [toPreset] before use.
 */
data class PresetDto(
    val name: String? = null,
    val main: String? = null,
    val submodules: Map<String, String>? = null,
    @com.google.gson.annotations.SerializedName("pull")
    val pull: Boolean? = null,
) {
    fun toPreset(): Preset = Preset(
        name = name ?: error("preset.name is required"),
        main = main ?: error("preset.main is required"),
        submodules = submodules ?: emptyMap(),
        pullEnabled = pull ?: true,
    )
}

/** Persistence container for [PresetDto] — parsed from JSON, then normalized to [PresetFile]. */
data class PresetFileDto(
    val presets: List<PresetDto> = emptyList(),
) {
    fun toPresetFile(): PresetFile = PresetFile(presets.map { it.toPreset() })
}

/** Persistence container — a list of presets serialized to JSON. */
data class PresetFile(
    val presets: List<Preset> = emptyList(),
)

/** Strategy for handling a dirty working tree during switch. */
enum class DirtyAction { Stash, Skip, Force }

/** User-configurable options for a switch operation. */
data class SwitchOptions(
    val dirty: DirtyAction = DirtyAction.Stash,
    val pull: Boolean = true,
    val fetchFirst: Boolean = true,
    val confirmBeforeInit: Boolean = false,
)

/**
 * Preflight check result for a single repo target.
 * [needsSwitch] is true when the repo exists and is on a different branch.
 * [branchMissing] is true when the repo exists but the target branch has no local or remote ref.
 */
data class PreflightRow(
    val label: String,
    val path: String,
    val target: String,
    val exists: Boolean,
    val current: String?,
    val dirtyCount: Int,
    val hasLocal: Boolean,
    val hasRemote: Boolean,
) {
    val isMain: Boolean get() = path == "."
    val needsSwitch: Boolean get() = exists && current != target
    /** True when the directory exists but the target branch doesn't exist locally or on origin. */
    val branchMissing: Boolean get() = exists && !hasLocal && !hasRemote
}
