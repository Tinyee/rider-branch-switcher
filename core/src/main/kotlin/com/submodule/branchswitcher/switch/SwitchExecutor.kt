package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import java.nio.file.Path

data class CheckpointEntry(
    val sha: String,
    val branch: String?,
)

/**
 * Orchestrates a branch switch by running a pipeline of [SwitchStep]s.
 *
 * Pipeline order (intentional):
 * 1. [DirtyHandlingStep] - stash/skip/force known repos before any branch changes
 * 2. Update main (fetch, checkout, pull) so its .gitmodules and gitlinks are current
 * 3. [SubmoduleSyncStep] - align URLs from the updated .gitmodules
 * 4. Update submodules (fetch existing repos, initialize missing repos, checkout, pull)
 *
 * Records a [CheckpointEntry] before switching for rollback support.
 */
class SwitchExecutor @JvmOverloads constructor(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: GitClient,
    private val cancellationHandle: CancellationHandle? = null,
    private val progressHandle: ProgressHandle? = null,
    private val cancelled: (() -> Boolean)? = null,
    private val onConfirmSubmoduleInit: ((String) -> Boolean)? = null,
    private val steps: List<SwitchStep> = listOf(
        DirtyHandlingStep(),
        FetchStep(SwitchTargetScope.MAIN),
        CheckoutStep(SwitchTargetScope.MAIN),
        PullStep(SwitchTargetScope.MAIN),
        SubmoduleSyncStep(),
        FetchStep(SwitchTargetScope.SUBMODULES),
        CheckoutStep(SwitchTargetScope.SUBMODULES),
        PullStep(SwitchTargetScope.SUBMODULES),
    ),
) {

    private var lastCheckpoint: Map<String, CheckpointEntry>? = null

    fun execute(request: ResolvedSwitchRequest): Boolean {
        val preset = request.preset
        val options = request.options
        log.activity("=== switching to preset: ${preset.name} ===")
        val context = SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = git,
            log = log,
            cancellationHandle = cancellationHandle,
            progressHandle = progressHandle,
            cancelled = { cancelled?.invoke() == true || cancellationHandle?.isCanceled == true },
            confirmBeforeInit = options.confirmBeforeInit,
            onConfirmSubmoduleInit = onConfirmSubmoduleInit,
        )

        // Record checkpoint before switching
        lastCheckpoint = recordCheckpoint(preset)

        context.progressHandle?.isIndeterminate = false

        var overallSuccess = true
        for (step in steps) {
            context.progressHandle?.text = step.name
            cancellationHandle?.checkCanceled()
            if (context.cancelled()) {
                git.cancel() // terminate in-flight command if any
                log.info("[cancelled] before step: ${step.name}")
                overallSuccess = false
                break
            }
            log.info("--- ${step.name} ---")
            when (val result = step.execute(context)) {
                is StepResult.Fatal -> {
                    log.error(" ${result.reason}")
                    overallSuccess = false
                    break
                }
                is StepResult.Partial -> {
                    result.failures.forEach { (path, msg) ->
                        log.warn("$path: $msg")
                    }
                    overallSuccess = false
                }
                is StepResult.Success -> { /* continue */ }
            }
        }
        log.info("")
        log.activity(if (overallSuccess) "=== done ===" else "=== done with errors ===")
        return overallSuccess
    }

    fun getCheckpoint(): Map<String, CheckpointEntry>? = lastCheckpoint

    fun rollback(): Boolean {
        val checkpoint = lastCheckpoint
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
            val cur = git.currentBranch(dir)
            // Already on the same branch as checkpoint - nothing to roll back
            if (entry.branch != null && entry.branch == cur) {
                log.debug("$label: still on ${entry.branch}, skip")
                continue
            }
            // Try to restore the original branch
            if (entry.branch != null) {
                log.activity("$label: checking out branch ${entry.branch} (was ${cur ?: "(detached)"})")
                val br = git.checkoutExisting(dir, entry.branch)
                if (!br.ok) {
                    log.warn("[rollback] $label branch checkout failed: ${br.stderr}, falling back to SHA")
                    val shaR = git.checkoutExisting(dir, entry.sha)
                    if (!shaR.ok) {
                        log.warn("[rollback] $label SHA checkout also failed: ${shaR.stderr}")
                        allOk = false
                    }
                }
            } else {
                // Was detached HEAD originally - restore to SHA only if different
                if (cur != entry.sha) {
                    log.activity("$label: resetting to ${entry.sha} (was on ${cur ?: "(detached)"})")
                    val r = git.checkoutExisting(dir, entry.sha)
                    if (!r.ok) {
                        log.warn("[rollback] $label checkout failed: ${r.stderr}")
                        allOk = false
                    }
                }
            }
        }
        log.activity(if (allOk) "=== rollback done ===" else "=== rollback done with errors ===")
        return allOk
    }

    private fun recordCheckpoint(preset: Preset): Map<String, CheckpointEntry> {
        val map = LinkedHashMap<String, CheckpointEntry>()
        for (target in preset.targets()) {
            val dir = resolveGitDir(projectRoot, target.path)
            if (!dir.exists() || !git.isGitRepo(dir)) continue
            val sha = git.revParseHead(dir) ?: continue
            val branch = git.currentBranch(dir)
            map[target.path] = CheckpointEntry(sha, branch)
        }
        return map
    }
}
