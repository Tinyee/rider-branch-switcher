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
    val pull: Boolean = true,
) {
    /** Returns all targets: main (".") first, then submodules. Main-first ordering is critical for submodule init. */
    fun targets(): List<RepoTarget> {
        val list = mutableListOf(RepoTarget(".", main))
        submodules.forEach { (path, branch) -> list += RepoTarget(path, branch) }
        return list
    }
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
