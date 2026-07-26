package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.model.isValidSubmodulePath
import java.nio.file.Path

sealed class StepResult {
    /** Step completed successfully, continue pipeline. */
    object Success : StepResult()
    /** Step failed fatally - pipeline must abort. */
    data class Fatal(val reason: String) : StepResult()
    /** Step completed with partial failures - continue but mark overall as warning. */
    data class Partial(val failures: Map<String, String>) : StepResult()
}

/** Immutable state passed explicitly between switch pipeline steps. */
class SwitchState private constructor(
    private val stashedPaths: Map<String, String>,
    private val skippedPaths: Set<String>,
    private val successfulCheckouts: Set<String>,
) {
    constructor() : this(emptyMap(), emptySet(), emptySet())

    fun withSkipped(path: String): SwitchState =
        SwitchState(stashedPaths, skippedPaths + path, successfulCheckouts)

    fun isSkipped(path: String): Boolean = path in skippedPaths

    fun withTrackedStash(path: String, message: String): SwitchState =
        SwitchState(stashedPaths + (path to message), skippedPaths, successfulCheckouts)

    fun withoutStash(path: String): SwitchState =
        SwitchState(stashedPaths - path, skippedPaths, successfulCheckouts)

    fun trackedStash(path: String): String? = stashedPaths[path]

    fun stashesSnapshot(): Map<String, String> = stashedPaths.toMap()

    fun withSuccessfulCheckout(path: String): SwitchState =
        SwitchState(stashedPaths, skippedPaths, successfulCheckouts + path)

    fun checkoutSucceeded(path: String): Boolean = path in successfulCheckouts

    fun hasStashes(): Boolean = stashedPaths.isNotEmpty()
}

data class StepExecution(
    val result: StepResult,
    val state: SwitchState,
)

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
    /** If true, show confirmation dialog before auto-init of missing submodules. */
    val confirmBeforeInit: Boolean = false,
    /** Callback for submodule init confirmation. The main module provides an IntelliJ dialog;
     *  core tests use a simple lambda. Returns false if init was declined. */
    val onConfirmSubmoduleInit: ((path: String) -> Boolean)? = null,
)

interface SwitchStep {
    /** Human-readable name for logging/progress display. */
    val name: String
    /** Execute this step and explicitly return the state for the next step. */
    fun execute(context: SwitchContext, state: SwitchState): StepExecution
}

/** Selects which repositories a staged switch step should process. */
enum class SwitchTargetScope { ALL, MAIN, SUBMODULES }

internal fun Preset.targetsFor(scope: SwitchTargetScope) = when (scope) {
    SwitchTargetScope.ALL -> targets()
    SwitchTargetScope.MAIN -> targets().filter { it.path == "." }
    // Initialize parent submodules before nested paths even if imported JSON used a different map order.
    SwitchTargetScope.SUBMODULES -> targets()
        .filter { it.path != "." }
        .sortedBy { it.path.count { separator -> separator == '/' } }
}

internal fun scopedStepName(action: String, scope: SwitchTargetScope): String = when (scope) {
    SwitchTargetScope.ALL -> action
    SwitchTargetScope.MAIN -> "$action main"
    SwitchTargetScope.SUBMODULES -> "$action submodules"
}

/** Resolve a target path to a [java.io.File] relative to the project root. */
fun resolveGitDir(root: java.nio.file.Path, path: String): java.io.File {
    val rootFile = root.toFile()
    if (path == ".") return rootFile
    require(isValidSubmodulePath(path)) { "invalid submodule path: '$path'" }
    val candidate = rootFile.resolve(path)
    val canonicalRoot = rootFile.canonicalFile
    val canonicalCandidate = candidate.canonicalFile
    require(canonicalCandidate != canonicalRoot && canonicalCandidate.toPath().startsWith(canonicalRoot.toPath())) {
        "submodule path escapes project root: '$path'"
    }
    return candidate
}
