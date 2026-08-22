package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.git.resolveHeadAndBranch
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import java.io.File
import java.nio.file.Path

internal data class RepositoryRecoveryAction(
    val repositoryPath: String,
    val targetSha: String,
    val targetBranch: String?,
    val expectedRepositoryId: String?,
)

internal data class StashRecoveryAction(
    val repositoryPath: String,
    val message: String,
    val oid: String?,
)

/** Immutable description of every side effect a recovery attempt may perform. */
internal data class SwitchRecoveryPlan(
    val repositories: List<RepositoryRecoveryAction>,
    val stashes: List<StashRecoveryAction>,
    val retainedInitializedSubmodules: Set<String>,
    val issues: List<OperationIssue> = emptyList(),
)

internal enum class RecoveryActionStatus { RESTORED, ALREADY_RESTORED, FAILED }

internal data class RepositoryRecoveryOutcome(
    val action: RepositoryRecoveryAction,
    val status: RecoveryActionStatus,
    val issue: OperationIssue? = null,
)

internal data class RecoveryExecutionResult(
    val outcomes: List<RepositoryRecoveryOutcome>,
    val planIssues: List<OperationIssue> = emptyList(),
) {
    val issues: List<OperationIssue> get() = planIssues + outcomes.mapNotNull(RepositoryRecoveryOutcome::issue)
    val ok: Boolean get() = issues.isEmpty()
}

/**
 * Summary of one completed recovery attempt. The plan/rollback internals stay
 * core-internal: callers only read the outcome summary ([ok], [issues], [rollbackOk])
 * and the final stash-restore state, so a plain class (no value semantics needed).
 */
class SwitchRecoveryOutcome internal constructor(
    internal val plan: SwitchRecoveryPlan,
    internal val rollback: RecoveryExecutionResult,
    val stashRestore: StashRestoreResult,
) {
    val rollbackOk: Boolean get() = rollback.ok
    val issues: List<OperationIssue> get() = rollback.issues + stashRestore.issues
    val ok: Boolean get() = issues.isEmpty()
}

/** Builds and executes idempotent recovery actions from a switch checkpoint. */
class SwitchRecoveryExecutor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
    /** Polled between repository actions so a user cancel stops a long rollback. */
    private val operationControl: OperationControl? = null,
) {
    internal fun plan(result: SwitchExecutionResult): SwitchRecoveryPlan {
        val checkpoint = result.checkpoint
        val planIssues = if (checkpoint == null) {
            listOf(recoveryIssue(".", OperationIssueCode.CHECKPOINT_UNAVAILABLE))
        } else {
            emptyList()
        }
        return SwitchRecoveryPlan(
            repositories = checkpoint.orEmpty().map { (path, entry) ->
                RepositoryRecoveryAction(path, entry.sha, entry.branch, entry.repositoryId)
            },
            stashes = result.state.stashesSnapshot().map { stash ->
                StashRecoveryAction(stash.repositoryPath, stash.message, stash.oid)
            },
            retainedInitializedSubmodules = result.state.initializedSubmodulesSnapshot(),
            issues = planIssues,
        )
    }

    /** Retries the stash actions captured by [plan] and keeps failed entries in state. */
    internal fun restoreTrackedStashes(
        plan: SwitchRecoveryPlan,
        state: SwitchState,
    ): StashRestoreResult = restoreTrackedStashes(
        projectRoot,
        git,
        log,
        state,
        plan.stashes.mapTo(linkedSetOf(), StashRecoveryAction::repositoryPath),
        control = operationControl,
        // Recovery rolled every repository back, so no repo reached the target: every
        // approved stash must be applied back (the file returns to its original path),
        // never dropped as authorized.
        discardApprovedFor = { false },
    )

    /** Plans once, then executes repository rollback and stash restoration independently. */
    @Suppress("TooGenericExceptionCaught") // recovery must still attempt stashes after repository failures
    fun recover(result: SwitchExecutionResult): SwitchRecoveryOutcome {
        val recoveryPlan = plan(result)
        val rollback = execute(recoveryPlan)
        val stashRestore = try {
            restoreTrackedStashes(recoveryPlan, result.state)
        } catch (error: SwitchStepException) {
            val cause = error.cause
            log.logFailure("[stash restore] exception", cause)
            StashRestoreResult(
                error.latestState,
                listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED, cause.diagnosticText())),
            )
        } catch (error: RuntimeException) {
            log.logFailure("[stash restore] exception", error)
            StashRestoreResult(
                result.state,
                listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED, error.diagnosticText())),
            )
        }
        return SwitchRecoveryOutcome(recoveryPlan, rollback, stashRestore)
    }

    /**
     * Builds the recovery plan for [result] and retries only the stash-restore step.
     * Used by the caller to recover WIP after an interrupted restore; the plan model
     * stays core-internal behind this single entry point.
     */
    fun retryStashRestore(result: SwitchExecutionResult): StashRestoreResult =
        restoreTrackedStashes(plan(result), result.state)

    /** Executes a previously inspected plan; each write is guarded by fresh repository checks. */
    internal fun execute(plan: SwitchRecoveryPlan): RecoveryExecutionResult {
        plan.retainedInitializedSubmodules.forEach { path ->
            // Retention is intentional (the switch may have initialized a submodule the
            // rolled-back superproject no longer registers). Warn with the cleanup path
            // so the orphan worktree is not silently left behind.
            log.warn(
                "[rollback] retained submodule initialized by this switch: $path; " +
                    "run 'git submodule deinit -f $path' to remove the orphan worktree",
            )
        }
        if (plan.repositories.isEmpty()) {
            log.debug("[rollback] no repository actions available")
            return RecoveryExecutionResult(emptyList(), plan.issues)
        }

        log.activity("=== rolling back to pre-switch state ===")
        val outcomes = mutableListOf<RepositoryRecoveryOutcome>()
        for (action in plan.repositories) {
            if (operationControl?.isCanceled == true) {
                // A user cancel stops the rollback between repositories; the write
                // lease is released as soon as the caller observes the completion.
                log.warn("[rollback] cancelled by user after ${outcomes.size}/${plan.repositories.size} repos")
                break
            }
            outcomes += recoverRepositorySafely(action)
        }
        val result = RecoveryExecutionResult(outcomes, plan.issues)
        log.activity(if (result.ok) "=== rollback done ===" else "=== rollback done with errors ===")
        return result
    }

    @Suppress("TooGenericExceptionCaught") // one repository failure must not prevent later recovery actions
    private fun recoverRepositorySafely(action: RepositoryRecoveryAction): RepositoryRecoveryOutcome =
        try {
            recoverRepository(action)
        } catch (error: RuntimeException) {
            log.logFailure("[rollback] ${labelFor(action.repositoryPath)} failed", error)
            failed(action, OperationIssueCode.RECOVERY_FAILED, error.diagnosticText())
        }

    private fun recoverRepository(action: RepositoryRecoveryAction): RepositoryRecoveryOutcome {
        val directory = resolveGitDir(projectRoot, action.repositoryPath)
        validateRepository(action, directory, requireClean = false)?.let { issue ->
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.FAILED, issue)
        }

        val label = labelFor(action.repositoryPath)
        // One atomic read (with a separate-read fallback) so a concurrent HEAD move can
        // never pair a SHA with the wrong branch name when judging "already restored".
        val head = git.resolveHeadAndBranch(directory)
        val currentBranch = head?.branch
        val currentSha = head?.sha
        if (currentBranch == action.targetBranch && currentSha == action.targetSha) {
            log.debug("$label: already at ${action.targetBranch ?: "(detached)"} (${action.targetSha}), skip")
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.ALREADY_RESTORED)
        }

        // Recovery writes (checkout / reset) fail on a stale index.lock, so block
        // with an actionable message instead of a checkout/reset "File exists" mystery.
        blockingLock(action, directory)?.let { return it }

        if (action.targetBranch == null) {
            return checkoutRevision(action, directory, action.targetSha, "checkpoint SHA")
        }

        if (currentBranch != action.targetBranch) {
            // Plain checkout is non-destructive: Git itself refuses conflicts.
            // A restored pre-switch stash may legitimately make this tree dirty.
            validateRepository(action, directory, requireClean = false)?.let { issue ->
                return RepositoryRecoveryOutcome(action, RecoveryActionStatus.FAILED, issue)
            }
            log.activity("$label: checking out branch ${action.targetBranch} (was ${currentBranch ?: "(detached)"})")
            blockingLock(action, directory)?.let { return it }
            val branchResult = git.checkoutExisting(directory, action.targetBranch)
            if (!branchResult.ok) {
                log.warn(
                    "[rollback] $label branch checkout failed: ${branchResult.diagnostic()}, " +
                        "falling back to SHA",
                )
                return checkoutRevision(action, directory, action.targetSha, "fallback SHA")
            }
        }

        return resetToCheckpoint(action, directory)
    }

    private fun checkoutRevision(
        action: RepositoryRecoveryAction,
        directory: File,
        revision: String,
        description: String,
    ): RepositoryRecoveryOutcome {
        validateRepository(action, directory, requireClean = false)?.let { issue ->
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.FAILED, issue)
        }
        val label = labelFor(action.repositoryPath)
        blockingLock(action, directory)?.let { return it }
        log.activity("$label: checking out $description $revision")
        val checkout = git.checkoutExisting(directory, revision)
        if (!checkout.ok) {
            log.warn("[rollback] $label $description checkout failed: ${checkout.diagnostic()}")
            return failed(action, OperationIssueCode.CHECKOUT_FAILED, checkout.diagnostic())
        }
        // A SHA checkout is detached. When a named checkpoint branch could not be
        // checked out, matching the SHA alone is only a partial recovery and must
        // not be reported as RESTORED.
        return verifyHead(action, directory, requireBranch = action.targetBranch != null && description == "fallback SHA")
    }

    private fun resetToCheckpoint(
        action: RepositoryRecoveryAction,
        directory: File,
    ): RepositoryRecoveryOutcome {
        if (git.revParseHead(directory) == action.targetSha) {
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.RESTORED)
        }
        validateRepository(action, directory, requireClean = true)?.let { issue ->
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.FAILED, issue)
        }
        val label = labelFor(action.repositoryPath)
        blockingLock(action, directory)?.let { return it }
        log.activity("$label: resetting HEAD to ${action.targetSha}")
        val reset = git.resetHard(directory, action.targetSha)
        if (!reset.ok) {
            log.warn("[rollback] $label reset failed: ${reset.diagnostic()}")
            return failed(action, OperationIssueCode.RECOVERY_FAILED, reset.diagnostic())
        }
        return verifyHead(action, directory)
    }

    private fun verifyHead(
        action: RepositoryRecoveryAction,
        directory: File,
        requireBranch: Boolean = false,
    ): RepositoryRecoveryOutcome {
        val actualSha = git.revParseHead(directory)
        val actualBranch = if (requireBranch) git.currentBranch(directory) else null
        if (actualSha == action.targetSha && (!requireBranch || actualBranch == action.targetBranch)) {
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.RESTORED)
        }
        log.warn(
            "[rollback] ${labelFor(action.repositoryPath)} postcondition failed: " +
                "expected=${action.targetSha}, actual=${actualSha ?: "unavailable"}",
        )
        return failed(
            action,
            OperationIssueCode.RECOVERY_FAILED,
            "expected HEAD ${action.targetSha} and branch ${action.targetBranch}, " +
                "actual HEAD ${actualSha ?: "unavailable"}, branch ${actualBranch ?: "detached or unavailable"}",
        )
    }

    private fun validateRepository(
        action: RepositoryRecoveryAction,
        directory: File,
        requireClean: Boolean,
    ): OperationIssue? {
        val label = labelFor(action.repositoryPath)
        if (!directory.exists() || !git.isGitRepo(directory)) {
            log.warn("[rollback] $label blocked: repository is missing")
            return recoveryIssue(action.repositoryPath, OperationIssueCode.REPOSITORY_MISSING)
        }
        val currentRepositoryId = git.repositoryIdentity(directory)?.gitDirectory
        if (action.expectedRepositoryId != null && currentRepositoryId != action.expectedRepositoryId) {
            log.warn("[rollback] $label blocked: repository identity changed")
            return recoveryIssue(action.repositoryPath, OperationIssueCode.REPOSITORY_IDENTITY_CHANGED)
        }
        if (requireClean && git.isDirty(directory)) {
            log.warn("[rollback] $label mutation blocked: working tree is dirty")
            return recoveryIssue(action.repositoryPath, OperationIssueCode.WORKTREE_DIRTY)
        }
        return null
    }

    private fun failed(
        action: RepositoryRecoveryAction,
        code: OperationIssueCode,
        diagnostic: String? = null,
        lockPath: String? = null,
    ) = RepositoryRecoveryOutcome(
        action,
        RecoveryActionStatus.FAILED,
        recoveryIssue(action.repositoryPath, code, diagnostic, lockPath),
    )

    /** Returns an INDEX_LOCK_BLOCKING outcome when a stale lock would block the next recovery write. */
    private fun blockingLock(
        action: RepositoryRecoveryAction,
        directory: File,
    ): RepositoryRecoveryOutcome? = git.indexLockFile(directory)?.let { lock ->
        log.warn(
            "[rollback] ${labelFor(action.repositoryPath)} blocked: stale index.lock at $lock; " +
                "delete it and retry",
        )
        failed(
            action,
            OperationIssueCode.INDEX_LOCK_BLOCKING,
            indexLockBlockedDiagnostic(lock),
            lockPath = lock,
        )
    }

    private fun labelFor(path: String): String = displayLabel(projectRoot, path)

    private fun Throwable.diagnosticText(): String = "${javaClass.simpleName}: $message"
}
