package com.submodule.branchswitcher.model

data class RepoTarget(
    val path: String,
    val branch: String,
)

data class Preset(
    val name: String,
    val main: String,
    val submodules: Map<String, String> = emptyMap(),
    val pull: Boolean = true,
) {
    fun targets(): List<RepoTarget> {
        val list = mutableListOf(RepoTarget(".", main))
        submodules.forEach { (path, branch) -> list += RepoTarget(path, branch) }
        return list
    }
}

data class PresetFile(
    val presets: List<Preset> = emptyList(),
)

enum class DirtyAction { Stash, Skip, Force }

data class SwitchOptions(
    val dirty: DirtyAction = DirtyAction.Stash,
    val pull: Boolean = true,
    val fetchFirst: Boolean = true,
)

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
    val branchMissing: Boolean get() = exists && !hasLocal && !hasRemote
}
