package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import java.io.File
import java.nio.file.Path

/** One repository blocked by an existing `index.lock`, with structured paths for localized UI. */
data class IndexLockBlock(
    val repositoryPath: String,
    val lockPath: String,
)

/**
 * Returns a description of every repository in [paths] whose git `index.lock` already exists.
 *
 * A stale lock makes every git write fail, and `git stash` fails on it silently
 * with exit 1 and no stderr. Write workflows surface the result before the first
 * mutation so the user sees exactly which repository is blocked instead of a
 * checkout/stash mystery failure. Read-only commands are unaffected by a lock.
 */
internal fun findBlockingIndexLocks(
    projectRoot: Path,
    git: GitRepositoryQuery,
    paths: Collection<String>,
    checkpoint: Map<String, CheckpointEntry> = emptyMap(),
): List<IndexLockBlock> = paths.mapNotNull { path ->
    val dir = resolveGitDir(projectRoot, path)
    val entry = checkpoint[path]
    if (entry != null) {
        val lock = if (entry.repositoryId != null && File(entry.repositoryId).isDirectory) {
            val candidate = File(entry.repositoryId, "index.lock")
            runCatching { candidate.canonicalPath }.getOrElse { candidate.path }
                .takeIf { candidate.exists() }
        } else {
            git.indexLockFile(dir)
        }
        return@mapNotNull lock?.let { IndexLockBlock(path, it) }
    }
    if (!dir.exists() || !git.isGitRepo(dir)) return@mapNotNull null
    git.indexLockFile(dir)?.let { lock -> IndexLockBlock(path, lock) }
}

/** Single source of the actionable guidance every write workflow shows for a stale lock. */
fun indexLockBlockedDiagnostic(lock: String): String =
    "stale git index.lock at $lock; if no other git process is running, delete it and retry"
