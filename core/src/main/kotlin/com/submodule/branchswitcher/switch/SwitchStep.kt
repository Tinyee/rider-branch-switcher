package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import java.nio.file.Path

sealed class StepResult {
    /** Step completed successfully, continue pipeline. */
    object Success : StepResult()
    /** Step failed fatally - pipeline must abort. */
    data class Fatal(val reason: String) : StepResult()
    /** Step completed with partial failures - continue but mark overall as warning. */
    data class Partial(val failures: Map<String, String>) : StepResult()
}

/**
 * Mutable state produced and consumed by switch pipeline steps.
 *
 * Keeping cross-step state behind named methods makes the pipeline contract
 * explicit: dirty handling decides skips/stashes, checkout records successful
 * repos, and pull/sync consume those decisions later.
 */
class SwitchPipelineState {
    private val stashedPaths: MutableMap<String, String> = linkedMapOf()
    private val skippedPaths: MutableSet<String> = linkedSetOf()
    private val successfulCheckouts: MutableSet<String> = linkedSetOf()

    fun markSkipped(path: String) {
        skippedPaths.add(path)
    }

    fun isSkipped(path: String): Boolean = path in skippedPaths

    fun trackStash(path: String, message: String) {
        stashedPaths[path] = message
    }

    fun consumeStash(path: String): String? = stashedPaths.remove(path)

    fun stashesSnapshot(): Map<String, String> = stashedPaths.toMap()

    fun markCheckoutSuccessful(path: String) {
        successfulCheckouts.add(path)
    }

    fun checkoutSucceeded(path: String): Boolean = path in successfulCheckouts

    fun hasStashes(): Boolean = stashedPaths.isNotEmpty()
    fun skippedSnapshot(): Set<String> = skippedPaths.toSet()
    fun successfulCheckoutsSnapshot(): Set<String> = successfulCheckouts.toSet()
}

data class SwitchContext(
    val projectRoot: Path,
    val preset: Preset,
    val options: SwitchOptions,
    val git: GitClient,
    val log: AppLogger,
    val cancellationHandle: CancellationHandle? = null,
    val progressHandle: ProgressHandle? = null,
    /** Mutable flag checked between/within steps for cancellation. */
    val cancelled: () -> Boolean = { false },
    /** Cross-step state shared by the switch pipeline. */
    val state: SwitchPipelineState = SwitchPipelineState(),
    /** If true, show confirmation dialog before auto-init of missing submodules. */
    val confirmBeforeInit: Boolean = false,
    /** Callback for submodule init confirmation. The main module provides an IntelliJ dialog;
     *  core tests use a simple lambda. Returns false if init was declined. */
    val onConfirmSubmoduleInit: ((path: String) -> Boolean)? = null,
)

interface SwitchStep {
    /** Human-readable name for logging/progress display. */
    val name: String
    /** Execute this step. Return the result. */
    fun execute(context: SwitchContext): StepResult
}

/** Resolve a target path to a [java.io.File] relative to the project root. */
fun resolveGitDir(root: java.nio.file.Path, path: String): java.io.File =
    if (path == ".") root.toFile() else root.resolve(path).toFile()
