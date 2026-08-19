package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.DeriveGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
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

    private fun runPreflight(targets: List<RepoTarget>, branchName: String): DerivePreflightResult {
        val eligibleTargets = mutableListOf<RepoTarget>()
        val outcomes = mutableListOf<DeriveRepositoryOutcome>()
        val topology = git.loadSubmoduleTopology(projectRoot.toFile())

        for (target in targets) {
            if (isCancelled()) break
            val outcome = inspectPreflightTarget(target, branchName, topology)
            if (outcome == null) eligibleTargets += target else outcomes += outcome
        }

        return DerivePreflightResult(
            eligibleTargets = eligibleTargets,
            outcomes = outcomes,
            cancelled = isCancelled(),
        )
    }

    private fun inspectPreflightTarget(
        target: RepoTarget,
        branchName: String,
        topology: SubmoduleTopology,
    ): DeriveRepositoryOutcome? {
        val directory = resolveGitDir(projectRoot, target.path)
        val label = labelFor(target.path)
        if (topology.isUnregistered(target.path)) {
            log.warn("[derive] $label: not registered in current .gitmodules graph - blocked")
            return target.outcome(DeriveRepositoryStatus.SKIPPED, OperationIssueCode.SUBMODULE_NOT_REGISTERED)
        }
        if (!directory.exists() || !git.isGitRepo(directory)) {
            log.warn("[derive] $label: not a git repo - blocked")
            return target.outcome(DeriveRepositoryStatus.SKIPPED, OperationIssueCode.REPOSITORY_MISSING)
        }

        // The probe wrapper keeps a lock-query failure (process capacity, start failure)
        // distinct from an actual detected lock: probe failure -> PREFLIGHT_FAILED.
        val lockProbe = probe(label, "index lock") { git.indexLockFile(directory) }
        if (lockProbe.failed) {
            return target.outcome(
                DeriveRepositoryStatus.PREFLIGHT_FAILED,
                OperationIssueCode.PREFLIGHT_FAILED,
                lockProbe.diagnostic,
            )
        }
        lockProbe.value?.let { lock ->
            log.warn("[derive] $label: stale index.lock blocks branch creation - delete it and retry: $lock")
            return target.outcome(
                DeriveRepositoryStatus.SKIPPED,
                OperationIssueCode.INDEX_LOCK_BLOCKING,
                indexLockBlockedDiagnostic(lock),
                lockPath = lock,
            )
        }

        val identity = git.repositoryIdentity(directory)
        val expectedGitDirectory = expectedSubmoduleGitDirectory(
            projectRoot.toFile(),
            topology.byPath[target.path],
            git,
        )
        if (isUnassociatedSubmoduleWorktree(
                projectRoot.toFile(),
                target.path,
                directory,
                identity,
                expectedGitDirectory,
            )
        ) {
            log.warn(
                "[derive] $label: repository is not associated with its superproject - blocked; " +
                    "actualGitDir=${identity?.gitDirectory}, expectedGitDir=$expectedGitDirectory, " +
                    "superproject=${identity?.superprojectRoot}",
            )
            return target.outcome(DeriveRepositoryStatus.SKIPPED, OperationIssueCode.REPOSITORY_IDENTITY_CHANGED)
        }

        val currentBranchProbe = probe(label, "current branch") { git.currentBranch(directory) }
        if (currentBranchProbe.failed) {
            return target.outcome(
                DeriveRepositoryStatus.PREFLIGHT_FAILED,
                OperationIssueCode.PREFLIGHT_FAILED,
                currentBranchProbe.diagnostic,
            )
        }
        val currentBranch = currentBranchProbe.value
        if (currentBranch == null) {
            log.warn("[derive] $label: detached HEAD or current branch unavailable - blocked")
            return target.outcome(DeriveRepositoryStatus.BRANCH_MISMATCH, OperationIssueCode.BRANCH_MISMATCH)
        }
        if (currentBranch != target.branch) {
            log.warn("[derive] $label: expected branch '${target.branch}', actual '$currentBranch' - blocked")
            return target.outcome(
                DeriveRepositoryStatus.BRANCH_MISMATCH,
                OperationIssueCode.BRANCH_MISMATCH,
                "expected=${target.branch}, actual=$currentBranch",
            )
        }

        val branchExistsProbe = probe(label, "branch existence") {
            git.localBranchProbe(directory, branchName)
        }
        if (branchExistsProbe.failed) {
            return target.outcome(
                DeriveRepositoryStatus.PREFLIGHT_FAILED,
                OperationIssueCode.PREFLIGHT_FAILED,
                branchExistsProbe.diagnostic,
            )
        }
        if (branchExistsProbe.value == true) {
            log.warn("[derive] $label: branch '$branchName' already exists - blocked")
            return target.outcome(DeriveRepositoryStatus.BRANCH_EXISTS, OperationIssueCode.BRANCH_ALREADY_EXISTS)
        }

        if (requireClean) {
            val dirtyProbe = probe(label, "dirty state") { git.dirtyProbe(directory) }
            if (dirtyProbe.failed) {
                return target.outcome(
                    DeriveRepositoryStatus.PREFLIGHT_FAILED,
                    OperationIssueCode.PREFLIGHT_FAILED,
                    dirtyProbe.diagnostic,
                )
            }
            if (dirtyProbe.value == true) {
                log.warn("[derive] $label: working tree is dirty - blocked")
                return target.outcome(DeriveRepositoryStatus.DIRTY, OperationIssueCode.WORKTREE_DIRTY)
            }
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught") // Git probe adapters vary; cancellation is rethrown
    private fun <T> probe(
        label: String,
        description: String,
        query: () -> T,
    ): ProbeResult<T> = try {
        ProbeResult(query())
    } catch (error: Exception) {
        rethrowIfCancellation(error)
        log.logFailure("[derive] $label: $description probe failed", error)
        ProbeResult(null, error)
    }

    private data class ProbeResult<T>(val value: T?, val error: Exception? = null) {
        val failed: Boolean get() = error != null
        val diagnostic: String? get() = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }

    @Suppress("TooGenericExceptionCaught") // Git query adapters vary; cancellation is rethrown
    private fun captureCheckpoint(eligibleTargets: List<RepoTarget>): DeriveCheckpointResult {
        val entries = LinkedHashMap<String, DeriveCheckpointEntry>()
        val checkpointFailures = mutableListOf<DeriveRepositoryOutcome>()

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
                    checkpointFailures += target.outcome(
                        DeriveRepositoryStatus.CHECKPOINT_FAILED,
                        OperationIssueCode.DERIVE_CHECKPOINT_FAILED,
                    )
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.logFailure("[derive] $repositoryLabel: checkpoint failed", e)
                checkpointFailures += target.outcome(
                    DeriveRepositoryStatus.CHECKPOINT_FAILED,
                    OperationIssueCode.DERIVE_CHECKPOINT_FAILED,
                    "${e.javaClass.simpleName}: ${e.message}",
                )
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
        val outcomes = mutableListOf<DeriveRepositoryOutcome>()
        var executionCancelled = false

        for (target in eligibleTargets) {
            if (isCancelled()) {
                executionCancelled = true
                break
            }
            val repositoryDirectory = resolveGitDir(projectRoot, target.path)
            val repositoryLabel = labelFor(target.path)

            try {
                // The preflight lock check happens a phase earlier; re-check immediately
                // before the write so a lock created in the gap surfaces as a structured
                // INDEX_LOCK_BLOCKING block instead of a generic branch-creation failure.
                // A lock-query failure is classified separately (GIT_QUERY_FAILED), not as
                // a branch-creation failure.
                val lockProbe = probe(repositoryLabel, "index lock") { git.indexLockFile(repositoryDirectory) }
                if (lockProbe.failed) {
                    outcomes += target.outcome(
                        DeriveRepositoryStatus.FAILED,
                        OperationIssueCode.GIT_QUERY_FAILED,
                        lockProbe.diagnostic,
                        OperationStage.DERIVE,
                    )
                    continue
                }
                val branchCreationLock = lockProbe.value
                if (branchCreationLock != null) {
                    log.warn(
                        "[derive] $repositoryLabel: stale index.lock blocks branch creation - " +
                            "delete it and retry: $branchCreationLock",
                    )
                    outcomes += target.outcome(
                        DeriveRepositoryStatus.FAILED,
                        OperationIssueCode.INDEX_LOCK_BLOCKING,
                        indexLockBlockedDiagnostic(branchCreationLock),
                        OperationStage.DERIVE,
                        lockPath = branchCreationLock,
                    )
                    continue
                }
                val checkoutResult = git.checkoutNewBranch(repositoryDirectory, branchName)
                if (checkoutResult.ok) {
                    outcomes += DeriveRepositoryOutcome(target.path, DeriveRepositoryStatus.SUCCEEDED)
                    log.activity("[derive] $repositoryLabel: created branch $branchName")
                } else {
                    val diagnostic = checkoutResult.diagnostic()
                    outcomes += target.outcome(
                        DeriveRepositoryStatus.FAILED,
                        OperationIssueCode.BRANCH_CREATE_FAILED,
                        diagnostic,
                        OperationStage.DERIVE,
                    )
                    log.warn("[derive] $repositoryLabel: FAILED - $diagnostic")
                }
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                log.logFailure("[derive] $repositoryLabel: branch creation exception", e)
                outcomes += target.outcome(
                    DeriveRepositoryStatus.FAILED,
                    OperationIssueCode.BRANCH_CREATE_FAILED,
                    "${e.javaClass.simpleName}: ${e.message}",
                    OperationStage.DERIVE,
                )
            }
        }

        return DeriveResult(
            outcomes = outcomes,
            checkpoint = checkpoint,
            cancelled = executionCancelled,
        )
    }

    /**
     * Rolls back succeeded repos: checkout original branch -> safe-delete derived branch.
     * Must be called in a non-cancelled operation for Git commands to execute.
     */
    @Suppress("TooGenericExceptionCaught") // rollback must continue after one repository fails
    fun rollbackSucceeded(
        deriveResult: DeriveResult,
        branchName: String,
        selectedPaths: Collection<String> = deriveResult.succeeded,
    ): DeriveRollbackResult {
        val pendingPaths = mutableListOf<String>()
        val succeededPaths = deriveResult.succeeded.toHashSet()
        val paths = selectedPaths.filterTo(mutableListOf()) { it in succeededPaths }

        for ((index, path) in paths.withIndex()) {
            try {
                val repositoryDirectory = resolveGitDir(projectRoot, path)
                val repositoryLabel = labelFor(path)
                val checkpointEntry = deriveResult.checkpoint[path]

                if (checkpointEntry != null) {
                    val currentRepositoryId = git.repositoryIdentity(repositoryDirectory)?.gitDirectory
                    if (checkpointEntry.repositoryId != null && currentRepositoryId != checkpointEntry.repositoryId) {
                        log.warn("[derive rollback] $repositoryLabel: repository identity changed - skipped")
                        pendingPaths.add(path)
                        continue
                    }
                    val rollbackLock = git.indexLockFile(repositoryDirectory)
                    if (rollbackLock != null) {
                        log.warn(
                            "[derive rollback] $repositoryLabel blocked: stale index.lock at " +
                                "$rollbackLock; delete it and retry",
                        )
                        pendingPaths.add(path)
                        continue
                    }
                    val restoreTarget = checkpointEntry.branch ?: checkpointEntry.sha
                    val checkoutResult = git.checkoutExisting(repositoryDirectory, restoreTarget)
                    if (!checkoutResult.ok) {
                        log.warn(
                            "[derive] $repositoryLabel: checkout rollback FAILED - " +
                                checkoutResult.diagnostic(),
                        )
                        pendingPaths.add(path)
                        continue
                    }
                    log.activity("[derive] $repositoryLabel: rolled back to $restoreTarget")

                    val deleteLock = git.indexLockFile(repositoryDirectory)
                    if (deleteLock != null) {
                        log.warn(
                            "[derive rollback] $repositoryLabel: cannot delete branch $branchName - " +
                                "stale index.lock at $deleteLock; delete it and retry",
                        )
                        pendingPaths.add(path)
                        continue
                    }
                    val deleteResult = git.deleteBranch(repositoryDirectory, branchName)
                    if (deleteResult.ok) {
                        log.activity("[derive] $repositoryLabel: deleted branch $branchName")
                    } else {
                        log.warn(
                            "[derive] $repositoryLabel: could not delete branch $branchName - " +
                                deleteResult.diagnostic(),
                        )
                        pendingPaths.add(path)
                    }
                } else {
                    log.warn("[derive] $repositoryLabel: no checkpoint entry, cannot rollback")
                    pendingPaths.add(path)
                }
            } catch (e: Exception) {
                if (classifier.isCancellation(e)) {
                    log.warn("[derive] rollback cancelled at $path; remaining paths deferred")
                    pendingPaths += paths.drop(index)
                    break
                }
                log.logFailure("[derive] $path: rollback exception", e)
                pendingPaths.add(path)
            }
        }

        return DeriveRollbackResult(pendingPaths.distinct())
    }

    private fun isCancelled(): Boolean = cancelled?.invoke() == true

    private fun labelFor(path: String): String = displayLabel(projectRoot, path)

    private fun RepoTarget.outcome(
        status: DeriveRepositoryStatus,
        code: OperationIssueCode,
        diagnostic: String? = null,
        stage: OperationStage = OperationStage.PREFLIGHT,
        lockPath: String? = null,
    ) = DeriveRepositoryOutcome(
        repositoryPath = path,
        status = status,
        issue = OperationIssue(
            stage = stage,
            code = code,
            repositoryPath = path,
            diagnostic = diagnostic,
            lockPath = lockPath,
        ),
    )

    private fun rethrowIfCancellation(e: Exception) {
        if (classifier.isCancellation(e)) throw e
    }
}

private data class DerivePreflightResult(
    val eligibleTargets: List<RepoTarget>,
    val outcomes: List<DeriveRepositoryOutcome>,
    val cancelled: Boolean,
) {
    val hasIssues: Boolean get() = outcomes.isNotEmpty()

    fun toDeriveResult(cancelled: Boolean = false): DeriveResult = DeriveResult(
        outcomes = outcomes,
        checkpoint = emptyMap(),
        cancelled = cancelled,
    )
}

private data class DeriveCheckpointResult(
    val entries: Map<String, DeriveCheckpointEntry>,
    val checkpointFailures: List<DeriveRepositoryOutcome>,
    val cancelled: Boolean,
) {
    fun toDeriveResult(
        cancelled: Boolean = false,
        includeEntries: Boolean = true,
    ): DeriveResult = DeriveResult(
        outcomes = checkpointFailures,
        checkpoint = if (includeEntries) entries else emptyMap(),
        cancelled = cancelled,
    )
}
