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
 *
 * [id] is a stable UUID that survives renames. Generated automatically for new presets
 * and auto-filled when loading old JSON that lacks an id field. History and future features
 * (shortcut bindings, color tags) reference presets by [id] rather than [name].
 */
/** Per-preset option overrides. Each field is null = use global default. */
data class PresetOverrides(
    val dirty: DirtyAction? = null,
    val pull: Boolean? = null,
    val fetchFirst: Boolean? = null,
)

data class Preset(
    val name: String,
    val main: String,
    val submodules: Map<String, String> = emptyMap(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val overrides: PresetOverrides? = null,
) {
    /** Returns all targets: main (".") first, then submodules. Main-first ordering is critical for submodule init. */
    fun targets(): List<RepoTarget> {
        val list = mutableListOf(RepoTarget(".", main))
        submodules.forEach { (path, branch) -> list += RepoTarget(path, branch) }
        return list
    }
}

/**
 * Gson-safe DTO for [Preset]. All fields are nullable so Gson doesn't silently
 * set Kotlin defaults to JVM zero-values (via UnsafeAllocator).
 * Always convert to [Preset] via [toPreset] before use.
 *
 * If [id] is missing (old JSON), [toPreset] auto-generates one.
 */
/** Gson-safe DTO for [PresetOverrides]. All fields nullable. */
data class PresetOverridesDto(
    val dirty: String? = null,
    val pull: Boolean? = null,
    val fetchFirst: Boolean? = null,
) {
    fun toOverrides(): PresetOverrides? {
        val d = dirty?.let { raw ->
            try { DirtyAction.valueOf(raw) }
            catch (_: IllegalArgumentException) { null }
        }
        if (d == null && pull == null && fetchFirst == null) return null
        return PresetOverrides(dirty = d, pull = pull, fetchFirst = fetchFirst)
    }
}

data class PresetDto(
    val id: String? = null,
    val name: String? = null,
    val main: String? = null,
    val submodules: Map<String, String>? = null,
    @com.google.gson.annotations.SerializedName("pull")
    val pull: Boolean? = null,
    val overrides: PresetOverridesDto? = null,
) {
    fun toPreset(explicitId: String? = null): Preset = Preset(
        id = explicitId ?: id ?: java.util.UUID.randomUUID().toString(),
        name = (name ?: error("preset.name is required")).trim(),
        main = (main ?: error("preset.main is required")).trim(),
        submodules = submodules ?: emptyMap(),
        overrides = migratePullAndOverrides(),
    )

    private fun migratePullAndOverrides(): PresetOverrides? {
        val ov = overrides?.toOverrides()
        val legacyPull = pull
        return when {
            legacyPull == null -> ov
            ov?.pull != null -> ov
            legacyPull == true -> ov
            else -> PresetOverrides(dirty = ov?.dirty, pull = false, fetchFirst = ov?.fetchFirst)
        }
    }

    val needsPullMigration: Boolean get() = pull != null
}

/** Persistence container for [PresetDto] — all fields nullable for Gson safety. */
data class PresetFileDto(
    val presets: List<PresetDto?>? = null,
) {
    fun toPresetFile(): PresetFile =
        PresetFile(presets.orEmpty().filterNotNull().map { it.toPreset() })
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

/** Merges [PresetOverrides] into global [SwitchOptions]. confirmBeforeInit always from global. */
fun PresetOverrides?.effectiveOptions(global: SwitchOptions): SwitchOptions {
    if (this == null) return global
    return SwitchOptions(
        dirty = dirty ?: global.dirty,
        pull = pull ?: global.pull,
        fetchFirst = fetchFirst ?: global.fetchFirst,
        confirmBeforeInit = global.confirmBeforeInit,
    )
}

/**
 * A switch request whose effective options have already been resolved.
 * Private constructor prevents entry points from pairing a preset with arbitrary options.
 */
class ResolvedSwitchRequest private constructor(
    val preset: Preset,
    val options: SwitchOptions,
) {
    companion object {
        fun resolve(preset: Preset, global: SwitchOptions): ResolvedSwitchRequest =
            ResolvedSwitchRequest(preset, options = preset.overrides.effectiveOptions(global))
    }
}

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
