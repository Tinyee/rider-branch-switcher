package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.SwitchOptions
import java.io.File
import java.nio.file.Path

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

    private var lastCheckpoint: Map<String, String>? = null

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

    fun getCheckpoint(): Map<String, String>? = lastCheckpoint

    fun rollback(): Boolean {
        val checkpoint = lastCheckpoint
        if (checkpoint == null || checkpoint.isEmpty()) {
            log("[rollback] no checkpoint available")
            return false
        }
        log("=== rolling back to pre-switch state ===")
        var allOk = true
        for ((path, sha) in checkpoint) {
            val dir = resolveDir(projectRoot, path)
            val label = if (path == ".") "<main>" else path
            if (!dir.exists() || !isGitRepo(dir)) {
                log("[rollback] skip $label — dir missing or not a repo")
                continue
            }
            val cur = git.currentBranch(dir)
            log("$label: resetting to $sha (was on ${cur ?: "(detached)"})")
            val r = runCatching {
                git.checkoutExisting(dir, sha)
            }
            if (r.isFailure) {
                log("[rollback] $label checkout failed: ${r.exceptionOrNull()?.message}")
                allOk = false
                continue
            }
            if (!r.getOrThrow().ok) {
                log("[rollback] $label checkout failed: ${r.getOrThrow().stderr}")
                allOk = false
            }
        }
        log(if (allOk) "=== rollback done ===" else "=== rollback done with errors ===")
        return allOk
    }

    private fun recordCheckpoint(preset: Preset): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (target in preset.targets()) {
            val dir = resolveDir(projectRoot, target.path)
            val head = if (dir.exists() && isGitRepo(dir)) git.revParseHead(dir) else null
            if (head != null) map[target.path] = head
        }
        return map
    }

    private fun resolveDir(root: Path, path: String): File =
        if (path == ".") root.toFile() else root.resolve(path).toFile()

    private fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
