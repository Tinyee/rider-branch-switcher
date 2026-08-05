package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Path

data class RepositoryRecoveryAction(
    val repositoryPath: String,
    val targetSha: String,
    val targetBranch: String?,
    val expectedRepositoryId: String?,
)

data class StashRecoveryAction(
    val repositoryPath: String,
    val message: String,
    val oid: String?,
)

/** Immutable description of every side effect a recovery attempt may perform. */
data class SwitchRecoveryPlan(
    val repositories: List<RepositoryRecoveryAction>,
    val stashes: List<StashRecoveryAction>,
    val retainedInitializedSubmodules: Set<String>,
    val issues: List<OperationIssue> = emptyList(),
)

enum class RecoveryActionStatus { RESTORED, ALREADY_RESTORED, FAILED }

data class RepositoryRecoveryOutcome(
    val action: RepositoryRecoveryAction,
    val status: RecoveryActionStatus,
    val issue: OperationIssue? = null,
)

data class RecoveryExecutionResult(
    val outcomes: List<RepositoryRecoveryOutcome>,
    val planIssues: List<OperationIssue> = emptyList(),
) {
    val issues: List<OperationIssue> get() = planIssues + outcomes.mapNotNull(RepositoryRecoveryOutcome::issue)
    val ok: Boolean get() = issues.isEmpty()
}

data class SwitchRecoveryOutcome(
    val plan: SwitchRecoveryPlan,
    val rollback: RecoveryExecutionResult,
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
) {
    fun plan(result: SwitchExecutionResult): SwitchRecoveryPlan {
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
            stashes = result.state.stashesSnapshot().map { (path, stash) ->
                StashRecoveryAction(path, stash.message, stash.oid)
            },
            retainedInitializedSubmodules = result.state.initializedSubmodulesSnapshot(),
            issues = planIssues,
        )
    }

    /** Retries the stash actions captured by [plan] and keeps failed entries in state. */
    fun restoreTrackedStashes(
        plan: SwitchRecoveryPlan,
        state: SwitchState,
    ): StashRestoreResult = restoreTrackedStashes(
        projectRoot,
        git,
        log,
        state,
        plan.stashes.mapTo(linkedSetOf(), StashRecoveryAction::repositoryPath),
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
            log.error("[stash restore] exception", cause)
            StashRestoreResult(
                error.latestState,
                listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED, cause.diagnosticText())),
            )
        } catch (error: RuntimeException) {
            log.error("[stash restore] exception", error)
            StashRestoreResult(
                result.state,
                listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED, error.diagnosticText())),
            )
        }
        return SwitchRecoveryOutcome(recoveryPlan, rollback, stashRestore)
    }

    /** Compatibility convenience for callers that only need the aggregate repository result. */
    fun rollback(result: SwitchExecutionResult): Boolean = execute(plan(result)).ok

    /** Executes a previously inspected plan; each write is guarded by fresh repository checks. */
    fun execute(plan: SwitchRecoveryPlan): RecoveryExecutionResult {
        plan.retainedInitializedSubmodules.forEach { path ->
            log.info("[rollback] retained submodule initialized by this switch: $path")
        }
        if (plan.repositories.isEmpty()) {
            log.debug("[rollback] no repository actions available")
            return RecoveryExecutionResult(emptyList(), plan.issues)
        }

        log.activity("=== rolling back to pre-switch state ===")
        val outcomes = plan.repositories.map(::recoverRepositorySafely)
        val result = RecoveryExecutionResult(outcomes, plan.issues)
        log.activity(if (result.ok) "=== rollback done ===" else "=== rollback done with errors ===")
        return result
    }

    @Suppress("TooGenericExceptionCaught") // one repository failure must not prevent later recovery actions
    private fun recoverRepositorySafely(action: RepositoryRecoveryAction): RepositoryRecoveryOutcome =
        try {
            recoverRepository(action)
        } catch (error: RuntimeException) {
            log.error("[rollback] ${labelFor(action.repositoryPath)} failed", error)
            failed(action, OperationIssueCode.RECOVERY_FAILED, error.diagnosticText())
        }

    private fun recoverRepository(action: RepositoryRecoveryAction): RepositoryRecoveryOutcome {
        val directory = resolveGitDir(projectRoot, action.repositoryPath)
        validateRepository(action, directory, requireClean = false)?.let { issue ->
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.FAILED, issue)
        }

        val label = labelFor(action.repositoryPath)
        val currentBranch = git.currentBranch(directory)
        val currentSha = git.revParseHead(directory)
        if (currentBranch == action.targetBranch && currentSha == action.targetSha) {
            log.debug("$label: already at ${action.targetBranch ?: "(detached)"} (${action.targetSha}), skip")
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.ALREADY_RESTORED)
        }

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
        log.activity("$label: checking out $description $revision")
        val checkout = git.checkoutExisting(directory, revision)
        if (!checkout.ok) {
            log.warn("[rollback] $label $description checkout failed: ${checkout.diagnostic()}")
            return failed(action, OperationIssueCode.CHECKOUT_FAILED, checkout.diagnostic())
        }
        return verifyHead(action, directory)
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
    ): RepositoryRecoveryOutcome {
        val actualSha = git.revParseHead(directory)
        if (actualSha == action.targetSha) {
            return RepositoryRecoveryOutcome(action, RecoveryActionStatus.RESTORED)
        }
        log.warn(
            "[rollback] ${labelFor(action.repositoryPath)} postcondition failed: " +
                "expected=${action.targetSha}, actual=${actualSha ?: "unavailable"}",
        )
        return failed(
            action,
            OperationIssueCode.RECOVERY_FAILED,
            "expected HEAD ${action.targetSha}, actual ${actualSha ?: "unavailable"}",
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
    ) = RepositoryRecoveryOutcome(
        action,
        RecoveryActionStatus.FAILED,
        recoveryIssue(action.repositoryPath, code, diagnostic),
    )

    private fun recoveryIssue(
        path: String,
        code: OperationIssueCode,
        diagnostic: String? = null,
    ) = OperationIssue(
        stage = OperationStage.RECOVERY,
        code = code,
        repositoryPath = path,
        severity = OperationIssueSeverity.ERROR,
        diagnostic = diagnostic,
    )

    private fun labelFor(path: String): String =
        if (path == ".") projectRoot.fileName.toString() else path

    private fun Throwable.diagnosticText(): String = "${javaClass.simpleName}: $message"
}
