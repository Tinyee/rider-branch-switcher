package com.submodule.branchswitcher.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * The repository root at or above [start], or null when the walk reaches the filesystem root
 * without finding a `.git` entry.
 *
 * A worktree's `.git` is a file rather than a directory; [Files.exists] accepts either, so this
 * resolves a worktree to itself.
 */
fun resolveGitRoot(start: Path): Path? {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) return current
        current = current.parent
    }
    return null
}
