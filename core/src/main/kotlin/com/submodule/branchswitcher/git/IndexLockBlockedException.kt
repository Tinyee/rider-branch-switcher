package com.submodule.branchswitcher.git

import java.io.File

/**
 * Raised by the git layer when an index/worktree mutation starts while the repository has
 * an existing `index.lock`. Carries the repository directory ([workDir]) and the lock path;
 * the workflow layer maps [workDir] to its display path, keeping preset/log privacy out of
 * the git layer.
 */
class IndexLockBlockedException(
    val workDir: File,
    val lockPath: String,
) : RuntimeException(
    "stale git index.lock at $lockPath; if no other git process is running, delete it and retry",
)
