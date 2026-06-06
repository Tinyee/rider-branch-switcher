package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.SwitchOptions
import java.io.File
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
)

class SwitchExecutor(
    private val projectRoot: Path,
    private val log: (String) -> Unit,
    private val git: GitClient = com.submodule.branchswitcher.git.GitOps(),
    private val indicator: com.intellij.openapi.progress.ProgressIndicator? = null,
) {
    private val steps: List<SwitchStep> = listOf(
        DirtyHandlingStep(),
        FetchStep(),
        CheckoutStep(),
        PullStep(),
        SubmoduleSyncStep(),
    )

    private var lastCheckpoint: Map<String, CheckpointEntry>? = null

    fun execute(preset: Preset, options: SwitchOptions): Boolean {
        log("=== switching to preset: ${preset.name} ===")
        val context = SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = git,
            log = log,
            indicator = indicator,
            cancelled = { indicator?.isCanceled ?: false },
        )

        // Record checkpoint before switching
        lastCheckpoint = recordCheckpoint(preset)

        var overallSuccess = true
        for (step in steps) {
            context.indicator?.text = step.name
            context.indicator?.checkCanceled()
            if (context.cancelled()) {
                log("[cancelled] before step: ${step.name}")
                overallSuccess = false
                break
            }
            log("--- ${step.name} ---")
            when (val result = step.execute(context)) {
                is StepResult.Fatal -> {
                    log("[fatal] ${result.reason}")
                    overallSuccess = false
                    break
                }
                is StepResult.Partial -> {
                    result.failures.forEach { (path, msg) ->
                        log("[fail] $path: $msg")
                    }
                    overallSuccess = false
                }
                is StepResult.Success -> { /* continue */ }
            }
        }
        log("")
        log(if (overallSuccess) "=== done ===" else "=== done with errors ===")
        return overallSuccess
    }

    fun getCheckpoint(): Map<String, CheckpointEntry>? = lastCheckpoint

    fun rollback(): Boolean {
        val checkpoint = lastCheckpoint
        if (checkpoint == null || checkpoint.isEmpty()) {
            log("[rollback] no checkpoint available")
            return false
        }
        log("=== rolling back to pre-switch state ===")
        var allOk = true
        for ((path, entry) in checkpoint) {
            val dir = resolveDir(projectRoot, path)
            val label = if (path == ".") "<main>" else path
            if (!dir.exists() || !isGitRepo(dir)) {
                log("[rollback] skip $label — dir missing or not a repo")
                continue
            }
            val cur = git.currentBranch(dir)
            // Restore branch first if we had one, otherwise fall back to SHA
            if (entry.branch != null && entry.branch != cur) {
                log("$label: checking out branch ${entry.branch} (was ${cur ?: "(detached)"})")
                val br = git.checkoutExisting(dir, entry.branch)
                if (!br.ok) {
                    log("[rollback] $label branch checkout failed: ${br.stderr}, falling back to SHA")
                    val shaR = git.checkoutExisting(dir, entry.sha)
                    if (!shaR.ok) {
                        log("[rollback] $label SHA checkout also failed: ${shaR.stderr}")
                        allOk = false
                        continue
                    }
                }
            } else if (cur != entry.sha) {
                log("$label: resetting to ${entry.sha} (was on ${cur ?: "(detached)"})")
                val r = git.checkoutExisting(dir, entry.sha)
                if (!r.ok) {
                    log("[rollback] $label checkout failed: ${r.stderr}")
                    allOk = false
                }
            }
        }
        log(if (allOk) "=== rollback done ===" else "=== rollback done with errors ===")
        return allOk
    }

    private fun recordCheckpoint(preset: Preset): Map<String, CheckpointEntry> {
        val map = LinkedHashMap<String, CheckpointEntry>()
        for (target in preset.targets()) {
            val dir = resolveDir(projectRoot, target.path)
            if (!dir.exists() || !isGitRepo(dir)) continue
            val sha = git.revParseHead(dir) ?: continue
            val branch = git.currentBranch(dir)
            map[target.path] = CheckpointEntry(sha, branch)
        }
        return map
    }

    private fun resolveDir(root: Path, path: String): File =
        if (path == ".") root.toFile() else root.resolve(path).toFile()

    private fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
