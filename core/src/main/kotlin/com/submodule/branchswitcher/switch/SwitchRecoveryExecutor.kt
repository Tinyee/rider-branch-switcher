package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import java.nio.file.Path

/** Restores repository and stash state recorded by a switch execution. */
class SwitchRecoveryExecutor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
) {
    /** Retries any stash restores left incomplete by cancellation or a failed pipeline tail. */
    fun restoreTrackedStashes(result: SwitchExecutionResult): StashRestoreResult =
        restoreTrackedStashes(projectRoot, git, log, result.state)

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
            if (entry.branch != null && entry.branch == currentBranch) {
                log.debug("$label: still on ${entry.branch}, skip")
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
                }
            } else if (currentBranch != entry.sha) {
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
}
