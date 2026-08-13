package com.submodule.branchswitcher.switch

/** If resolved [SwitchOptions.pull] is enabled, pull --ff-only for each target. */
class PullStep(
    private val scope: SwitchTargetScope = SwitchTargetScope.ALL,
) : SwitchStep {
    override val name = scopedStepName("pull", scope)
    override val stage = OperationStage.PULL

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        if (!context.options.pull) {
            val restore = restoreTrackedStashes(context, state)
            val result = if (restore.issues.isEmpty()) {
                StepResult.Success
            } else {
                StepResult.Partial(restore.issues)
            }
            return StepExecution(result, restore.state)
        }

        val issues = mutableListOf<OperationIssue>()
        for (target in context.preset.targetsFor(scope)) {
            val repositoryDirectory = resolveGitDir(context.projectRoot, target.path)
            if (!repositoryDirectory.exists() || !context.git.isGitRepo(repositoryDirectory)) continue
            // Only pull on repos where checkout actually succeeded
            if (!state.checkoutSucceeded(target.path)) {
                context.log.info("[skip] pull - checkout did not succeed for ${target.path}")
                continue
            }
            val currentBranch = context.git.currentBranch(repositoryDirectory)
            if (currentBranch != target.branch) {
                context.log.info(
                    "[skip] pull - current branch is '${currentBranch ?: "(detached)"}', " +
                        "expected '${target.branch}'",
                )
                continue
            }
            val pullResult = context.git.pullFf(repositoryDirectory, target.branch)
            if (pullResult.ok) {
                context.log.info("pull ok - ${target.path}")
            } else {
                context.log.warn(" pull failed (kept local): ${pullResult.diagnostic()}")
                issues += OperationIssue(
                    stage = stage,
                    code = OperationIssueCode.PULL_FAILED,
                    repositoryPath = target.path,
                    diagnostic = pullResult.diagnostic(),
                )
            }
        }
        val restore = restoreTrackedStashes(context, state)
        issues += restore.issues
        val result = if (issues.isEmpty()) StepResult.Success else StepResult.Partial(issues)
        return StepExecution(result, restore.state)
    }

    /** Restore stashes that were created during DirtyHandlingStep, now that checkout + pull are done. */
    private fun restoreTrackedStashes(context: SwitchContext, state: SwitchState): StashRestoreResult {
        val selectedPaths = context.preset.targetsFor(scope).mapTo(hashSetOf()) { it.path }
        return restoreTrackedStashes(
            context.projectRoot, context.git, context.log, state, selectedPaths,
        )
    }
}

data class StashRestoreResult(
    val state: SwitchState,
    val issues: List<OperationIssue>,
)

/** Restores tracked stashes and retains failed entries so a later recovery can retry them. */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // preserve successfully restored entries if a later Git query fails
internal fun restoreTrackedStashes(
    projectRoot: java.nio.file.Path,
    git: com.submodule.branchswitcher.git.SwitchGitClient,
    log: com.submodule.branchswitcher.log.AppLogger,
    state: SwitchState,
    selectedPaths: Set<String>? = null,
): StashRestoreResult {
    val issues = mutableListOf<OperationIssue>()
    var nextState = state
    try {
        for ((path, stash) in state.stashesSnapshot()) {
            if (selectedPaths != null && path !in selectedPaths) continue
            val repositoryDirectory = resolveGitDir(projectRoot, path)
            if (stash.restoreAttempted) {
                log.warn("[fail] stash restore not retried for $path; a previous apply may have changed the worktree")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_RESTORE_FAILED,
                    repositoryPath = path,
                    severity = OperationIssueSeverity.ERROR,
                    diagnostic = "restore already attempted; recovery backup retained for manual inspection",
                )
                continue
            }
            if (stash.oid == null) {
                log.error("[fail] stash restore skipped - identity unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                    repositoryPath = path,
                    severity = OperationIssueSeverity.ERROR,
                )
                continue
            }
            if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
                log.warn("[fail] stash apply skipped - repository unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_REPOSITORY_UNAVAILABLE,
                    repositoryPath = path,
                )
                continue
            }
            val existingLock = git.indexLockFile(repositoryDirectory)
            if (existingLock != null) {
                log.warn(
                    "[fail] stash apply skipped - stale index.lock at $existingLock; " +
                        "delete it and retry (${stash.message})",
                )
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                    repositoryPath = path,
                    diagnostic = indexLockBlockedDiagnostic(existingLock),
                    lockPath = existingLock,
                )
                continue
            }
            val applyResult = try {
                git.stashApply(repositoryDirectory, stash.oid)
            } catch (error: IndexLockBlockedException) {
                // WriteGuard can observe a lock after the initial probe and throw
                // before Git starts. This is safe to retry after the lock is removed.
                nextState = nextState.withStashRestoreRetryable(path)
                throw SwitchStepException(nextState, error)
            } catch (error: RuntimeException) {
                // Other exceptions may have reached Git and must remain at-most-once.
                nextState = nextState.withStashRestoreAttempted(path)
                throw SwitchStepException(nextState, error)
            }
            if (applyResult.ok) {
                log.info("stash apply ok; recovery backup retained (${stash.message}, oid=${stash.oid})")
                nextState = nextState.withRestoredStashBackup(path)
            } else {
                // A lock created between the earlier check and the apply must surface as
                // the structured lock block, not a generic stash-apply failure.
                val racedLock = git.indexLockFile(repositoryDirectory)
                if (racedLock != null) {
                    // The preflight check proved the tree was unlocked, and the
                    // follow-up proved the failure was the lock race. No Git apply
                    // started, so this entry remains safe to retry.
                    nextState = nextState.withStashRestoreRetryable(path)
                    log.warn(
                        "[fail] stash apply blocked by stale index.lock at $racedLock; " +
                            "delete it and retry (${stash.message})",
                    )
                    issues += OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                        repositoryPath = path,
                        diagnostic = indexLockBlockedDiagnostic(racedLock),
                        lockPath = racedLock,
                    )
                } else {
                    nextState = nextState.withStashRestoreAttempted(path)
                    log.warn("[fail] stash apply failed for $path: ${applyResult.diagnostic()}")
                    issues += OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.STASH_RESTORE_FAILED,
                        repositoryPath = path,
                        diagnostic = applyResult.diagnostic(),
                    )
                }
            }
        }
    } catch (e: SwitchStepException) {
        val lock = e.cause as? IndexLockBlockedException
        if (lock == null) throw e
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = lock.repositoryPath,
            severity = OperationIssueSeverity.ERROR,
            diagnostic = indexLockBlockedDiagnostic(lock.lockPath),
            lockPath = lock.lockPath,
        )
        return StashRestoreResult(nextState, issues)
    } catch (e: RuntimeException) {
        throw SwitchStepException(nextState, e)
    }
    return StashRestoreResult(nextState, issues)
}
