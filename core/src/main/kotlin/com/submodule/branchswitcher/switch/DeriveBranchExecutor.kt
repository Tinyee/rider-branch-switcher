package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.nio.file.Path

/**
 * Executes a derive-branch operation across all repos in a preset.
 *
 * State machine:
 * 1. Preflight  - atomic gate: branch mismatch / exists / missing / dirty / probe-error -> blocked
 * 2. Checkpoint - atomic gate: every validTarget must have revParseHead
 * 3. Execute    - per-target try/catch so exceptions never lose rollback info
 *
 * [rollbackSucceeded] is a separate call so the caller decides the operation scope.
 *
 * @param requireClean If true (default), dirty repos block the operation.
 */
class DeriveBranchExecutor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: GitClient,
    private val cancelled: (() -> Boolean)? = null,
    private val requireClean: Boolean = true,
) {


    fun execute(preset: Preset, branchName: String): DeriveResult {
        val targets = preset.targets()
        val validTargets = mutableListOf<RepoTarget>()
        val branchExists = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dirty = mutableListOf<String>()
        val branchMismatch = mutableListOf<String>()
        val preflightError = mutableListOf<String>()

        // -- Phase 1: Preflight (atomic gate) ----------------------------------
        for (target in targets) {
            if (cancelled?.invoke() == true) break
            val dir = resolveGitDir(projectRoot, target.path)
            val label = if (target.path == ".") projectRoot.fileName.toString() else target.path

            if (!dir.exists() || !git.isGitRepo(dir)) {
                log.warn("[derive] $label: not a git repo - blocked")
                skipped.add(target.path)
                continue
            }

            // Base branch gate: repo must be on the preset's named target branch.
            val expectedBranch = target.branch
            val current = try {
                git.currentBranch(dir)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $label: cannot detect current branch - ${e.message}")
                preflightError.add(target.path)
                continue
            }
            if (current == null) {
                log.warn("[derive] $label: detached HEAD or current branch unavailable - blocked")
                branchMismatch.add(target.path)
                continue
            }
            if (current != expectedBranch) {
                log.warn("[derive] $label: expected branch '$expectedBranch', actual '$current' - blocked")
                branchMismatch.add(target.path)
                continue
            }

            // Branch existence probe (fail-closed: null -> error)
            val probe = try {
                git.localBranchProbe(dir, branchName)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $label: branch existence probe failed - ${e.message}")
                null
            }
            if (probe == null) {
                log.warn("[derive] $label: cannot check branch existence - blocked")
                preflightError.add(target.path)
                continue
            }
            if (probe) {
                log.warn("[derive] $label: branch '$branchName' already exists - blocked")
                branchExists.add(target.path)
                continue
            }

            // Dirty probe (fail-closed when requireClean: null -> error)
            if (requireClean) {
                val dp = try {
                    git.dirtyProbe(dir)
                } catch (e: Exception) {
                    rethrowIfCancellation(e)
                    log.warn("[derive] $label: dirty probe failed - ${e.message}")
                    null
                }
                if (dp == null) {
                    log.warn("[derive] $label: cannot check dirty status - blocked")
                    preflightError.add(target.path)
                    continue
                }
                if (dp) {
                    log.warn("[derive] $label: working tree is dirty - blocked")
                    dirty.add(target.path)
                    continue
                }
            }

            validTargets.add(target)
        }

        if (cancelled?.invoke() == true) {
            return DeriveResult(emptyList(), branchExists, skipped, dirty, branchMismatch, preflightError, emptyList(), emptyMap(), emptyMap(), cancelled = true)
        }

        val anyPreflightIssue = branchExists.isNotEmpty() || skipped.isNotEmpty() || dirty.isNotEmpty() ||
            branchMismatch.isNotEmpty() || preflightError.isNotEmpty()
        if (anyPreflightIssue) {
            log.warn("[derive] preflight blocked - no repos modified")
            return DeriveResult(emptyList(), branchExists, skipped, dirty, branchMismatch, preflightError, emptyList(), emptyMap(), emptyMap())
        }

        // -- Phase 2: Checkpoint (atomic gate) ---------------------------------
        val checkpoint = LinkedHashMap<String, DeriveCheckpointEntry>()
        val checkpointFailed = mutableListOf<String>()
        for (target in validTargets) {
            if (cancelled?.invoke() == true) break
            val dir = resolveGitDir(projectRoot, target.path)
            val label = if (target.path == ".") projectRoot.fileName.toString() else target.path
            try {
                val sha = git.revParseHead(dir)
                if (sha != null) {
                    val branch = git.currentBranch(dir)
                    checkpoint[target.path] = DeriveCheckpointEntry(sha, branch)
                } else {
                    log.warn("[derive] $label: no HEAD - cannot checkpoint")
                    checkpointFailed.add(target.path)
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $label: checkpoint failed - ${e.message}")
                checkpointFailed.add(target.path)
            }
        }

        if (cancelled?.invoke() == true) {
            return DeriveResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), checkpointFailed, emptyMap(), checkpoint, cancelled = true)
        }

        if (checkpointFailed.isNotEmpty()) {
            log.warn("[derive] checkpoint incomplete for ${checkpointFailed.size} repo(s) - blocked")
            return DeriveResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), checkpointFailed, emptyMap(), emptyMap())
        }

        // -- Phase 3: Execute (per-target safety) ------------------------------
        val succeeded = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        var execCancelled = false

        for (target in validTargets) {
            if (cancelled?.invoke() == true) { execCancelled = true; break }
            val dir = resolveGitDir(projectRoot, target.path)
            val label = if (target.path == ".") projectRoot.fileName.toString() else target.path

            try {
                val r = git.checkoutNewBranch(dir, branchName)
                if (r.ok) {
                    succeeded.add(target.path)
                    log.activity("[derive] $label: created branch $branchName")
                } else {
                    val err = r.stderr.lines().firstOrNull() ?: "exit ${r.exitCode}"
                    failed[target.path] = err
                    log.warn("[derive] $label: FAILED - $err")
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $label: exception - ${e.javaClass.simpleName}: ${e.message}")
                failed[target.path] = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return DeriveResult(succeeded, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), failed, checkpoint, cancelled = execCancelled)
    }

    /**
     * Rolls back succeeded repos: checkout original branch -> safe-delete derived branch.
     * Must be called in a non-cancelled operation for Git commands to execute.
     */
    fun rollbackSucceeded(result: DeriveResult, branchName: String): List<String> {
        val rollbackFailures = mutableListOf<String>()

        for (path in result.succeeded) {
            try {
                val dir = resolveGitDir(projectRoot, path)
                val label = if (path == ".") projectRoot.fileName.toString() else path
                val entry = result.checkpoint[path]

                if (entry != null) {
                    val target = entry.branch ?: entry.sha
                    val co = git.checkoutExisting(dir, target)
                    if (!co.ok) {
                        log.warn("[derive] $label: checkout rollback FAILED - ${co.stderr.lines().firstOrNull() ?: "exit ${co.exitCode}"}")
                        rollbackFailures.add(path)
                        continue
                    }
                    log.activity("[derive] $label: rolled back to $target")

                    val del = git.deleteBranch(dir, branchName)
                    if (del.ok) {
                        log.activity("[derive] $label: deleted branch $branchName")
                    } else {
                        log.warn("[derive] $label: could not delete branch $branchName - ${del.stderr.lines().firstOrNull() ?: "exit ${del.exitCode}"}")
                        rollbackFailures.add(path)
                    }
                } else {
                    log.warn("[derive] $label: no checkpoint entry, cannot rollback")
                    rollbackFailures.add(path)
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $path: rollback exception - ${e.javaClass.simpleName}: ${e.message}")
                rollbackFailures.add(path)
            }
        }

        return rollbackFailures
    }
}

private fun rethrowIfCancellation(e: Exception) {
    if (e is java.util.concurrent.CancellationException) throw e
}
