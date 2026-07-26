package com.submodule.branchswitcher.platform

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun resolveGitRoot(start: Path): Path? {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) return current
        current = current.parent
    }
    return null
}

fun Project.gitRootPath(): Path? = basePath?.let(Paths::get)?.let(::resolveGitRoot)
