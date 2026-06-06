package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import java.nio.file.Path

class SwitchExecutor(
    private val projectRoot: Path,
    private val log: (String) -> Unit,
    private val git: GitClient = com.submodule.branchswitcher.git.GitOps(),
) {
    private val steps: List<SwitchStep> = listOf(
        DirtyHandlingStep(),
        FetchStep(),
        CheckoutStep(),
        PullStep(),
        SubmoduleSyncStep(),
    )

    fun execute(preset: Preset, options: SwitchOptions): Boolean {
        log("=== switching to preset: ${preset.name} ===")
        val context = SwitchContext(
            projectRoot = projectRoot,
            preset = preset,
            options = options,
            git = git,
            log = log,
        )
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
}
