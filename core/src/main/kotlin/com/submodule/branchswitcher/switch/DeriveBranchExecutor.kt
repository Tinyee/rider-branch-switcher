package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.DeriveGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.nio.file.Path

/**
 * Executes a derive-branch operation across all repos in a preset.
 *
 * State machine:
 * 1. Preflight  - atomic gate: branch mismatch / exists / missing / dirty / probe-error -> blocked
 * 2. Checkpoint - atomic gate: every eligible target must have revParseHead
 * 3. Execute    - per-target try/catch so exceptions never lose rollback info
 *
 * [rollbackSucceeded] is a separate call so the caller decides the operation scope.
 *
 * @param requireClean If true (default), dirty repos block the operation.
 */
class DeriveBranchExecutor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: DeriveGitClient,
    private val cancelled: (() -> Boolean)? = null,
    private val requireClean: Boolean = true,
    private val classifier: CancellationClassifier = CancellationClassifier.DEFAULT,
) {

    fun execute(preset: Preset, branchName: String): DeriveResult {
        val preflight = runPreflight(preset.targets(), branchName)
        if (preflight.cancelled) {
            return preflight.toDeriveResult(cancelled = true)
        }
        if (preflight.hasIssues) {
            log.warn("[derive] preflight blocked - no repos modified")
            return preflight.toDeriveResult()
        }

        val checkpoint = captureCheckpoint(preflight.eligibleTargets)
        if (checkpoint.cancelled) {
            return checkpoint.toDeriveResult(cancelled = true)
        }
        if (checkpoint.checkpointFailures.isNotEmpty()) {
            log.warn(
                "[derive] checkpoint incomplete for ${checkpoint.checkpointFailures.size} repo(s) - blocked",
            )
            return checkpoint.toDeriveResult(includeEntries = false)
        }

        return createBranches(preflight.eligibleTargets, branchName, checkpoint.entries)
    }

    @Suppress("TooGenericExceptionCaught") // Git query adapters vary; cancellation is rethrown
    private fun runPreflight(targets: List<RepoTarget>, branchName: String): DerivePreflightResult {
        val eligibleTargets = mutableListOf<RepoTarget>()
        val branchExists = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dirty = mutableListOf<String>()
        val branchMismatch = mutableListOf<String>()
        val probeFailures = mutableListOf<String>()
        val root = projectRoot.toFile()
        val topology = git.loadSubmoduleTopology(root)

        for (target in targets) {
            if (isCancelled()) break
            val repositoryDirectory = resolveGitDir(projectRoot, target.path)
            val repositoryLabel = labelFor(target.path)

            if (topology.isUnregistered(target.path)) {
                log.warn("[derive] $repositoryLabel: not registered in current .gitmodules graph - blocked")
                skipped.add(target.path)
                continue
            }

            if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
                log.warn("[derive] $repositoryLabel: not a git repo - blocked")
                skipped.add(target.path)
                continue
            }
            val repositoryIdentity = git.repositoryIdentity(repositoryDirectory)
            val expectedGitDirectory = expectedSubmoduleGitDirectory(root, topology.byPath[target.path], git)
            if (isUnassociatedSubmoduleWorktree(
                    projectRoot.toFile(),
                    target.path,
                    repositoryDirectory,
                    repositoryIdentity,
                    expectedGitDirectory,
                )
            ) {
                log.warn(
                    "[derive] $repositoryLabel: repository is not associated with its superproject - blocked; " +
                        "actualGitDir=${repositoryIdentity?.gitDirectory}, " +
                        "expectedGitDir=$expectedGitDirectory, " +
                        "superproject=${repositoryIdentity?.superprojectRoot}",
                )
                skipped.add(target.path)
                continue
            }

            // Base branch gate: repo must be on the preset's named target branch.
            val expectedBranch = target.branch
            val currentBranch = try {
                git.currentBranch(repositoryDirectory)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $repositoryLabel: cannot detect current branch", e)
                probeFailures.add(target.path)
                continue
            }
            if (currentBranch == null) {
                log.warn("[derive] $repositoryLabel: detached HEAD or current branch unavailable - blocked")
                branchMismatch.add(target.path)
                continue
            }
            if (currentBranch != expectedBranch) {
                log.warn(
                    "[derive] $repositoryLabel: expected branch '$expectedBranch', " +
                        "actual '$currentBranch' - blocked",
                )
                branchMismatch.add(target.path)
                continue
            }

            // Branch existence probe (fail-closed: null -> error)
            val branchAlreadyExists = try {
                git.localBranchProbe(repositoryDirectory, branchName)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $repositoryLabel: branch existence probe failed", e)
                null
            }
            if (branchAlreadyExists == null) {
                log.warn("[derive] $repositoryLabel: cannot check branch existence - blocked")
                probeFailures.add(target.path)
                continue
            }
            if (branchAlreadyExists) {
                log.warn("[derive] $repositoryLabel: branch '$branchName' already exists - blocked")
                branchExists.add(target.path)
                continue
            }

            // Dirty probe (fail-closed when requireClean: null -> error)
            if (requireClean) {
                val isDirty = try {
                    git.dirtyProbe(repositoryDirectory)
                } catch (e: Exception) {
                    rethrowIfCancellation(e)
                    log.warn("[derive] $repositoryLabel: dirty probe failed", e)
                    null
                }
                if (isDirty == null) {
                    log.warn("[derive] $repositoryLabel: cannot check dirty status - blocked")
                    probeFailures.add(target.path)
                    continue
                }
                if (isDirty) {
                    log.warn("[derive] $repositoryLabel: working tree is dirty - blocked")
                    dirty.add(target.path)
                    continue
                }
            }

            eligibleTargets.add(target)
        }

        return DerivePreflightResult(
            eligibleTargets = eligibleTargets,
            branchExists = branchExists,
            skipped = skipped,
            dirty = dirty,
            branchMismatch = branchMismatch,
            probeFailures = probeFailures,
            cancelled = isCancelled(),
        )
    }

    @Suppress("TooGenericExceptionCaught") // Git query adapters vary; cancellation is rethrown
    private fun captureCheckpoint(eligibleTargets: List<RepoTarget>): DeriveCheckpointResult {
        val entries = LinkedHashMap<String, DeriveCheckpointEntry>()
        val checkpointFailures = mutableListOf<String>()

        for (target in eligibleTargets) {
            if (isCancelled()) break
            val repositoryDirectory = resolveGitDir(projectRoot, target.path)
            val repositoryLabel = labelFor(target.path)
            try {
                val sha = git.revParseHead(repositoryDirectory)
                if (sha != null) {
                    val branch = git.currentBranch(repositoryDirectory)
                    val repositoryId = git.repositoryIdentity(repositoryDirectory)?.gitDirectory
                    entries[target.path] = DeriveCheckpointEntry(
                        sha,
                        branch,
                        repositoryId,
                    )
                    log.info(
                        "[derive checkpoint] $repositoryLabel: branch=${branch ?: "(detached)"}, " +
                            "head=${sha.take(12)}, gitDir=${repositoryId ?: "unknown"}",
                    )
                } else {
                    log.warn("[derive] $repositoryLabel: no HEAD - cannot checkpoint")
                    checkpointFailures.add(target.path)
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $repositoryLabel: checkpoint failed", e)
                checkpointFailures.add(target.path)
            }
        }

        return DeriveCheckpointResult(
            entries = entries,
            checkpointFailures = checkpointFailures,
            cancelled = isCancelled(),
        )
    }

    @Suppress("TooGenericExceptionCaught") // preserve per-repository failures while rethrowing cancellation
    private fun createBranches(
        eligibleTargets: List<RepoTarget>,
        branchName: String,
        checkpoint: Map<String, DeriveCheckpointEntry>,
    ): DeriveResult {
        val succeeded = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        var executionCancelled = false

        for (target in eligibleTargets) {
            if (isCancelled()) {
                executionCancelled = true
                break
            }
            val repositoryDirectory = resolveGitDir(projectRoot, target.path)
            val repositoryLabel = labelFor(target.path)

            try {
                val checkoutResult = git.checkoutNewBranch(repositoryDirectory, branchName)
                if (checkoutResult.ok) {
                    succeeded.add(target.path)
                    log.activity("[derive] $repositoryLabel: created branch $branchName")
                } else {
                    val diagnostic = checkoutResult.diagnostic()
                    failed[target.path] = diagnostic
                    log.warn("[derive] $repositoryLabel: FAILED - $diagnostic")
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $repositoryLabel: branch creation exception", e)
                failed[target.path] = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return DeriveResult(
            succeeded = succeeded,
            branchExists = emptyList(),
            skipped = emptyList(),
            dirty = emptyList(),
            branchMismatch = emptyList(),
            preflightError = emptyList(),
            checkpointFailed = emptyList(),
            failed = failed,
            checkpoint = checkpoint,
            cancelled = executionCancelled,
        )
    }

    /**
     * Rolls back succeeded repos: checkout original branch -> safe-delete derived branch.
     * Must be called in a non-cancelled operation for Git commands to execute.
     */
    @Suppress("TooGenericExceptionCaught") // rollback must continue after one repository fails
    fun rollbackSucceeded(deriveResult: DeriveResult, branchName: String): List<String> {
        val rollbackFailures = mutableListOf<String>()

        for (path in deriveResult.succeeded) {
            try {
                val repositoryDirectory = resolveGitDir(projectRoot, path)
                val repositoryLabel = labelFor(path)
                val checkpointEntry = deriveResult.checkpoint[path]

                if (checkpointEntry != null) {
                    val currentRepositoryId = git.repositoryIdentity(repositoryDirectory)?.gitDirectory
                    if (checkpointEntry.repositoryId != null && currentRepositoryId != checkpointEntry.repositoryId) {
                        log.warn("[derive rollback] $repositoryLabel: repository identity changed - skipped")
                        rollbackFailures.add(path)
                        continue
                    }
                    val restoreTarget = checkpointEntry.branch ?: checkpointEntry.sha
                    val checkoutResult = git.checkoutExisting(repositoryDirectory, restoreTarget)
                    if (!checkoutResult.ok) {
                        log.warn(
                            "[derive] $repositoryLabel: checkout rollback FAILED - " +
                                checkoutResult.diagnostic(),
                        )
                        rollbackFailures.add(path)
                        continue
                    }
                    log.activity("[derive] $repositoryLabel: rolled back to $restoreTarget")

                    val deleteResult = git.deleteBranch(repositoryDirectory, branchName)
                    if (deleteResult.ok) {
                        log.activity("[derive] $repositoryLabel: deleted branch $branchName")
                    } else {
                        log.warn(
                            "[derive] $repositoryLabel: could not delete branch $branchName - " +
                                deleteResult.diagnostic(),
                        )
                        rollbackFailures.add(path)
                    }
                } else {
                    log.warn("[derive] $repositoryLabel: no checkpoint entry, cannot rollback")
                    rollbackFailures.add(path)
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.warn("[derive] $path: rollback exception", e)
                rollbackFailures.add(path)
            }
        }

        return rollbackFailures
    }

    private fun isCancelled(): Boolean = cancelled?.invoke() == true

    private fun labelFor(path: String): String =
        if (path == ".") projectRoot.fileName.toString() else path

    private fun rethrowIfCancellation(e: Exception) {
        if (classifier.isCancellation(e)) throw e
    }
}

private data class DerivePreflightResult(
    val eligibleTargets: List<RepoTarget>,
    val branchExists: List<String>,
    val skipped: List<String>,
    val dirty: List<String>,
    val branchMismatch: List<String>,
    val probeFailures: List<String>,
    val cancelled: Boolean,
) {
    val hasIssues: Boolean
        get() = branchExists.isNotEmpty() ||
            skipped.isNotEmpty() ||
            dirty.isNotEmpty() ||
            branchMismatch.isNotEmpty() ||
            probeFailures.isNotEmpty()

    fun toDeriveResult(cancelled: Boolean = false): DeriveResult = DeriveResult(
        succeeded = emptyList(),
        branchExists = branchExists,
        skipped = skipped,
        dirty = dirty,
        branchMismatch = branchMismatch,
        preflightError = probeFailures,
        checkpointFailed = emptyList(),
        failed = emptyMap(),
        checkpoint = emptyMap(),
        cancelled = cancelled,
    )
}

private data class DeriveCheckpointResult(
    val entries: Map<String, DeriveCheckpointEntry>,
    val checkpointFailures: List<String>,
    val cancelled: Boolean,
) {
    fun toDeriveResult(
        cancelled: Boolean = false,
        includeEntries: Boolean = true,
    ): DeriveResult = DeriveResult(
        succeeded = emptyList(),
        branchExists = emptyList(),
        skipped = emptyList(),
        dirty = emptyList(),
        branchMismatch = emptyList(),
        preflightError = emptyList(),
        checkpointFailed = checkpointFailures,
        failed = emptyMap(),
        checkpoint = if (includeEntries) entries else emptyMap(),
        cancelled = cancelled,
    )
}
