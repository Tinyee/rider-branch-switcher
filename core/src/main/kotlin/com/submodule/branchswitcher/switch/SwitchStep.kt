package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.model.isValidSubmodulePath
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Control-flow result returned by one pipeline step.
 *
 * A partial result records per-repository failures but allows later repositories
 * and cleanup steps to run. Pipeline-aborting failures are not modeled as a
 * step result: they surface as thrown [SwitchStepException]s, which the executor
 * catches and folds into a failed execution.
 */
sealed class StepResult {
    /** Step completed successfully, continue pipeline. */
    object Success : StepResult()
    /** Step completed with partial failures - continue but mark overall as warning. */
    data class Partial(val issues: List<OperationIssue>) : StepResult()
}

/** Folds a step's collected issues into the pipeline control-flow result. */
internal fun List<OperationIssue>.toStepResult(): StepResult =
    if (isEmpty()) StepResult.Success else StepResult.Partial(this)

/**
 * Immutable record of side effects completed by earlier pipeline steps.
 *
 * Recovery depends on this state after exceptions and cancellation, so a step
 * must return a new instance immediately after each successful side effect.
 */
data class TrackedStash(
    val message: String,
    val oid: String?,
    val restoreAttempted: Boolean = false,
)

class SwitchState private constructor(
    private val stashedPaths: Map<String, TrackedStash>,
    private val skippedPaths: Set<String>,
    private val successfulCheckouts: Set<String>,
    private val initializedSubmodules: Set<String>,
    private val retainedStashBackups: Set<String>,
    private val frozenTargetShas: Map<String, String>,
) {
    constructor() : this(emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())

    /**
     * Records the target revision [branch] resolved to at discard time, so a later checkout
     * of the same target can verify HEAD still matches (collision revalidation and checkout
     * then provably operate on the same tree).
     */
    fun withFrozenTargetSha(path: String, sha: String): SwitchState =
        copy(frozenTargetShas = frozenTargetShas + (path to sha))

    fun frozenTargetSha(path: String): String? = frozenTargetShas[path]

    fun withSkipped(path: String): SwitchState =
        copy(skippedPaths = skippedPaths + path)

    fun isSkipped(path: String): Boolean = path in skippedPaths

    fun withTrackedStash(path: String, message: String, oid: String?): SwitchState =
        copy(stashedPaths = stashedPaths + (path to TrackedStash(message, oid)))

    fun withRestoredStashBackup(path: String): SwitchState =
        copy(stashedPaths = stashedPaths - path, retainedStashBackups = retainedStashBackups + path)

    /** Removes a successfully applied-and-dropped stash; no recovery backup is retained. */
    fun withStashRestored(path: String): SwitchState =
        copy(stashedPaths = stashedPaths - path)

    fun withStashRestoreAttempted(path: String): SwitchState {
        val stash = stashedPaths[path] ?: return this
        return copy(stashedPaths = stashedPaths + (path to stash.copy(restoreAttempted = true)))
    }

    fun withStashRestoreRetryable(path: String): SwitchState {
        val stash = stashedPaths[path] ?: return this
        return copy(stashedPaths = stashedPaths + (path to stash.copy(restoreAttempted = false)))
    }

    fun trackedStash(path: String): TrackedStash? = stashedPaths[path]

    fun stashesSnapshot(): Map<String, TrackedStash> = stashedPaths.toMap()

    fun withSuccessfulCheckout(path: String): SwitchState =
        copy(successfulCheckouts = successfulCheckouts + path)

    fun checkoutSucceeded(path: String): Boolean = path in successfulCheckouts

    fun withInitializedSubmodule(path: String): SwitchState =
        copy(initializedSubmodules = initializedSubmodules + path)

    fun initializedSubmodulesSnapshot(): Set<String> = initializedSubmodules.toSet()

    fun retainedStashBackupsSnapshot(): Set<String> = retainedStashBackups.toSet()

    private fun copy(
        stashedPaths: Map<String, TrackedStash> = this.stashedPaths,
        skippedPaths: Set<String> = this.skippedPaths,
        successfulCheckouts: Set<String> = this.successfulCheckouts,
        initializedSubmodules: Set<String> = this.initializedSubmodules,
        retainedStashBackups: Set<String> = this.retainedStashBackups,
        frozenTargetShas: Map<String, String> = this.frozenTargetShas,
    ): SwitchState = SwitchState(
        stashedPaths,
        skippedPaths,
        successfulCheckouts,
        initializedSubmodules,
        retainedStashBackups,
        frozenTargetShas,
    )
}

/** The decision and updated state produced by one [SwitchStep]. */
data class StepExecution(
    val result: StepResult,
    val state: SwitchState,
)

/**
 * Carries the latest immutable state across an exceptional step boundary.
 *
 * Stateful steps wrap failures after every completed side effect so the
 * executor can still return a recoverable structured result.
 */
internal class SwitchStepException(
    val latestState: SwitchState,
    override val cause: RuntimeException,
) : RuntimeException(cause)

/**
 * Stable dependencies and request data shared by all steps in one switch.
 *
 * Mutable operation progress belongs in [SwitchState], not in this context.
 * IntelliJ-specific progress and dialogs enter core through narrow callbacks.
 */
data class SwitchContext(
    val projectRoot: Path,
    val preset: Preset,
    val options: SwitchOptions,
    val git: SwitchGitClient,
    val log: AppLogger,
    val cancellationHandle: CancellationHandle? = null,
    val progressHandle: ProgressHandle? = null,
    /** Mutable flag checked between/within steps for cancellation. */
    val cancelled: () -> Boolean = { false },
    /** If true, missing submodule directories must be pre-approved before the switch starts. */
    val confirmBeforeInit: Boolean = false,
    /** Submodule paths the user approved for initialization before execution (no worker-time dialogs). */
    val preApprovedSubmoduleInit: Set<String> = emptySet(),
    /** File paths (per repo path, "." = main) the user approved for discard before switching. Empty when none. */
    val approvedCollisionDiscards: Map<String, Set<String>> = emptyMap(),
    /** Pre-switch repository identities used by later topology safety gates. */
    val checkpoint: Map<String, CheckpointEntry> = emptyMap(),
)

/**
 * One ordered stage of a preset switch.
 *
 * Implementations may touch several repositories, but must report every
 * completed state change through [StepExecution.state].
 */
interface SwitchStep {
    /** Human-readable name for logging/progress display. */
    val name: String
    val stage: OperationStage
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
@Suppress("TooGenericExceptionCaught") // path resolution failures must fail closed regardless of the underlying cause
fun resolveGitDir(root: java.nio.file.Path, path: String): java.io.File {
    val rootFile = root.toFile()
    if (path == ".") return rootFile
    require(isValidSubmodulePath(path)) { "invalid submodule path: '$path'" }
    val candidate = rootFile.resolve(path)
    // The escape check must fail closed: an unresolvable path (permission error,
    // damaged symlink) is refused rather than compared lexically, where a link
    // escaping the project root could otherwise slip through.
    val canonicalRoot = try {
        Paths.get(rootFile.resolvedIdentity())
    } catch (e: Exception) {
        throw IllegalArgumentException("cannot resolve project root: $root", e)
    }
    val canonicalCandidate = try {
        Paths.get(candidate.resolvedIdentity())
    } catch (e: Exception) {
        throw IllegalArgumentException("cannot resolve submodule path '$path'; refusing unsafe path", e)
    }
    require(canonicalCandidate != canonicalRoot && canonicalCandidate.startsWith(canonicalRoot)) {
        "submodule path escapes project root: '$path'"
    }
    return candidate
}

/** Human-readable repository label: the project directory name for the main repo, else the target path. */
internal fun displayLabel(root: Path, path: String): String =
    if (path == ".") root.fileName.toString() else path

/** Advances a bounded progress handle to [index]/[total] with a repository label. */
internal fun ProgressHandle.updateProgress(index: Int, total: Int, root: Path, path: String) {
    fraction = index.toDouble() / total
    text2 = displayLabel(root, path)
}
