package com.submodule.branchswitcher.model

/**
 * Validates the branch shorthand accepted by `git check-ref-format --branch`.
 * This intentionally fails closed before starting a multi-repository operation.
 */
fun isValidBranchName(name: String): Boolean {
    if (name.isEmpty() || name != name.trim()) return false
    if (name == "@" || name.startsWith("-")) return false
    if (name.startsWith("/") || name.endsWith("/")) return false
    if (name.contains("//")) return false
    if (name.contains("..") || name.contains("@{")) return false
    if (name.any { it.code <= 32 || it.code == 127 || it in "~^:?*[\\\\" }) return false

    return name.split('/').none { component ->
        component.startsWith(".") ||
            component.endsWith(".") ||
            component.endsWith(".lock")
    }
}

/** Returns true only for normalized, project-relative Git submodule paths. */
fun isValidSubmodulePath(path: String): Boolean {
    if (path.isEmpty() || path != path.trim()) return false
    if (path == "." || path == "..") return false
    if (path.startsWith("/") || path.startsWith("\\")) return false
    if ('\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path)) return false
    return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** Returns the first invalid preset field, or null when the preset is safe to use. */
fun presetValidationError(preset: Preset): String? {
    if (preset.name.isBlank()) return "preset name is blank"
    if (!isValidBranchName(preset.main)) return "invalid main branch: '${preset.main}'"
    for ((path, branch) in preset.submodules) {
        if (!isValidSubmodulePath(path)) return "invalid submodule path: '$path'"
        if (!isValidBranchName(branch)) return "invalid branch for '$path': '$branch'"
    }
    return null
}

fun requireValidPreset(preset: Preset): Preset {
    val error = presetValidationError(preset)
    require(error == null) { error.orEmpty() }
    return preset
}
