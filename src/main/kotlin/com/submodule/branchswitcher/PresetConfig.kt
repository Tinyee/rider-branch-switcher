package com.submodule.branchswitcher

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
