package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import java.nio.file.Path

data class SwitchRecoveryOutcome(
    val rollbackOk: Boolean,
    val stashRestore: StashRestoreResult,
) {
    val ok: Boolean get() = rollbackOk && stashRestore.failures.isEmpty()
}

/** Restores repository and stash state recorded by a switch execution. */
class SwitchRecoveryExecutor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
) {
    /** Retries any stash restores left incomplete by cancellation or a failed pipeline tail. */
    fun restoreTrackedStashes(result: SwitchExecutionResult): StashRestoreResult =
        restoreTrackedStashes(projectRoot, git, log, result.state)

    /** Attempts branch rollback and stash restoration independently, then combines their results. */
    @Suppress("TooGenericExceptionCaught") // recovery must continue with stash restoration after rollback exceptions
    fun recover(result: SwitchExecutionResult): SwitchRecoveryOutcome {
        val rollbackOk = try {
            result.checkpoint.isNullOrEmpty() || rollback(result)
        } catch (e: RuntimeException) {
            log.error("[rollback] exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        val stashRestore = try {
            restoreTrackedStashes(result)
        } catch (e: SwitchStepException) {
            val error = e.cause
            log.error("[stash restore] exception: ${error.javaClass.simpleName}: ${error.message}")
            StashRestoreResult(e.latestState, mapOf("." to "stash recovery exception"))
        } catch (e: RuntimeException) {
            log.error("[stash restore] exception: ${e.javaClass.simpleName}: ${e.message}")
            StashRestoreResult(result.state, mapOf("." to "stash recovery exception"))
        }
        return SwitchRecoveryOutcome(rollbackOk, stashRestore)
    }

    fun rollback(result: SwitchExecutionResult): Boolean {
        val checkpoint = result.checkpoint
        if (checkpoint == null || checkpoint.isEmpty()) {
            log.debug("[rollback] no checkpoint available")
            return false
        }
        log.activity("=== rolling back to pre-switch state ===")
        var allOk = true
        for ((path, entry) in checkpoint) {
            val dir = resolveGitDir(projectRoot, path)
            val label = if (path == ".") projectRoot.fileName.toString() else path
            if (!dir.exists() || !git.isGitRepo(dir)) {
                log.debug("[rollback] skip $label - dir missing or not a repo")
                allOk = false
                continue
            }
            val currentBranch = git.currentBranch(dir)
            val currentSha = git.revParseHead(dir)
            if (entry.branch != null && entry.branch == currentBranch) {
                if (currentSha == entry.sha) {
                    log.debug("$label: already at ${entry.branch} (${entry.sha}), skip")
                } else if (!resetToCheckpoint(dir, label, entry.sha)) {
                    allOk = false
                }
                continue
            }
            if (entry.branch != null) {
                log.activity("$label: checking out branch ${entry.branch} (was ${currentBranch ?: "(detached)"})")
                val branchResult = git.checkoutExisting(dir, entry.branch)
                if (!branchResult.ok) {
                    log.warn(
                        "[rollback] $label branch checkout failed: " +
                            "${branchResult.diagnostic()}, falling back to SHA",
                    )
                    val shaResult = git.checkoutExisting(dir, entry.sha)
                    if (!shaResult.ok) {
                        log.warn("[rollback] $label SHA checkout also failed: ${shaResult.diagnostic()}")
                        allOk = false
                    }
                } else if (!resetToCheckpoint(dir, label, entry.sha)) {
                    allOk = false
                }
            } else if (currentBranch != null || currentSha != entry.sha) {
                log.activity("$label: resetting to ${entry.sha} (was on ${currentBranch ?: "(detached)"})")
                val result = git.checkoutExisting(dir, entry.sha)
                if (!result.ok) {
                    log.warn("[rollback] $label checkout failed: ${result.diagnostic()}")
                    allOk = false
                }
            }
        }
        log.activity(if (allOk) "=== rollback done ===" else "=== rollback done with errors ===")
        return allOk
    }

    private fun resetToCheckpoint(dir: java.io.File, label: String, sha: String): Boolean {
        if (git.revParseHead(dir) == sha) return true
        if (git.isDirty(dir)) {
            log.warn("[rollback] $label reset blocked: working tree is dirty")
            return false
        }
        log.activity("$label: resetting HEAD to $sha")
        val reset = git.resetHard(dir, sha)
        if (!reset.ok) {
            log.warn("[rollback] $label reset failed: ${reset.diagnostic()}")
            return false
        }
        return true
    }
}
