package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import java.nio.file.Path

/**
 * Returns a human-readable description of every repository in [paths] whose git
 * `index.lock` file already exists.
 *
 * A stale lock makes every git write fail, and `git stash` fails on it silently
 * with exit 1 and no stderr. Write workflows surface the result before the first
 * mutation so the user sees exactly which repository is blocked instead of a
 * checkout/stash mystery failure. Read-only commands are unaffected by a lock.
 */
fun findBlockingIndexLocks(
    projectRoot: Path,
    git: GitRepositoryQuery,
    paths: Collection<String>,
): List<String> = paths.mapNotNull { path ->
    val dir = resolveGitDir(projectRoot, path)
    if (!dir.exists() || !git.isGitRepo(dir)) return@mapNotNull null
    git.indexLockFile(dir)?.let { lock ->
        if (path == ".") lock else "$path -> $lock"
    }
}
