package com.submodule.branchswitcher.switch

import com.intellij.openapi.progress.ProgressIndicator
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import java.nio.file.Path

sealed class StepResult {
    /** Step completed successfully, continue pipeline. */
    object Success : StepResult()
    /** Step failed fatally — pipeline must abort. */
    data class Fatal(val reason: String) : StepResult()
    /** Step completed with partial failures — continue but mark overall as warning. */
    data class Partial(val failures: Map<String, String>) : StepResult()
}

data class SwitchContext(
    val projectRoot: Path,
    val preset: Preset,
    val options: SwitchOptions,
    val git: GitClient,
    val log: (String) -> Unit,
    val indicator: ProgressIndicator? = null,
    /** Mutable flag checked between/within steps for cancellation. */
    val cancelled: () -> Boolean = { false },
    /** Tracks stashed repos: path -> stash message. CheckoutStep auto-pops on return. */
    val stashedPaths: MutableMap<String, String> = mutableMapOf(),
    /** If true, show confirmation dialog before auto-init of missing submodules. */
    val confirmBeforeInit: Boolean = false,
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

/** Check whether [dir] is a git repository. */
fun isGitRepo(dir: java.io.File): Boolean = java.io.File(dir, ".git").exists()

/** Returns the last path segment, stripping trailing `~`. Used for display labels. */
fun shortLabel(path: String): String {
    if (!path.contains("/")) return path
    return path.substringAfterLast('/').removeSuffix("~")
}
