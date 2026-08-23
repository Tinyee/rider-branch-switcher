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
 * Why a stash was created, so recovery can decide apply (restore the work) vs drop
 * (authorize the discard) independently for each entry.
 */
enum class StashPurpose {
    /** The dirty tree's WIP backup; always applied back, then dropped on success. */
    WIP_RESTORE_AFTER_SWITCH,
    /** An approved collision file isolated for discard; dropped on a successful switch of its repo, applied back otherwise. */
    APPROVED_DISCARD,
}

/**
 * Immutable record of side effects completed by earlier pipeline steps.
 *
 * Recovery depends on this state after exceptions and cancellation, so a step
 * must return a new instance immediately after each successful side effect.
 */
data class TrackedStash(
    val id: String,
    val repositoryPath: String,
    val purpose: StashPurpose,
    val message: String,
    val oid: String?,
    val restoreAttempted: Boolean = false,
    val creationOrder: Int,
    /** The approved collision paths isolated in this stash (APPROVED_DISCARD only); the drop is authorized only after re-verifying them against the actual checked-out tree. */
    val approvedPaths: Set<String> = emptySet(),
)

class SwitchState private constructor(
    private val trackedStashes: List<TrackedStash>,
    private val skippedPaths: Set<String>,
    private val successfulCheckouts: Set<String>,
    private val initializedSubmodules: Set<String>,
    private val retainedStashBackups: Set<String>,
) {
    constructor() : this(emptyList(), emptySet(), emptySet(), emptySet(), emptySet())

    fun withSkipped(path: String): SwitchState =
        copy(skippedPaths = skippedPaths + path)

    fun isSkipped(path: String): Boolean = path in skippedPaths

    /**
     * Records a newly created stash. A repository may hold several stashes (an approved
     * stash, then a WIP stash), so entries are keyed by [TrackedStash.id], never by
     * repository path. [creationOrder] is assigned in creation order; recovery restores in
     * reverse order (WIP before approved).
     */
    fun withTrackedStash(
        path: String,
        purpose: StashPurpose,
        message: String,
        oid: String?,
        approvedPaths: Set<String> = emptySet(),
    ): SwitchState {
        val creationOrder = (trackedStashes.maxOfOrNull { it.creationOrder } ?: -1) + 1
        return copy(
            trackedStashes = trackedStashes + TrackedStash(
                id = "stash-$creationOrder",
                repositoryPath = path,
                purpose = purpose,
                message = message,
                oid = oid,
                creationOrder = creationOrder,
                approvedPaths = approvedPaths,
            ),
        )
    }

    /** Retains [id] as a recovery backup after a failed drop; the entry leaves tracking. */
    fun withRestoredStashBackup(id: String): SwitchState =
        copy(
            trackedStashes = trackedStashes.filterNot { it.id == id },
            retainedStashBackups = retainedStashBackups + id,
        )

    /** Removes a successfully applied-and-dropped stash; no recovery backup is retained. */
    fun withStashRestored(id: String): SwitchState =
        copy(trackedStashes = trackedStashes.filterNot { it.id == id })

    fun withStashRestoreAttempted(id: String): SwitchState =
        copy(trackedStashes = trackedStashes.map { if (it.id == id) it.copy(restoreAttempted = true) else it })

    fun withStashRestoreRetryable(id: String): SwitchState =
        copy(trackedStashes = trackedStashes.map { if (it.id == id) it.copy(restoreAttempted = false) else it })

    /** The first tracked stash for [path] (callers with several stashes per repo use [stashesSnapshot]). */
    fun trackedStash(path: String): TrackedStash? = trackedStashes.firstOrNull { it.repositoryPath == path }

    /** All tracked stashes, in creation order. */
    fun stashesSnapshot(): List<TrackedStash> = trackedStashes.toList()

    /** The next approved-discard round for [path] (the number of approved stashes already recorded for it). */
    fun approvedStashRound(path: String): Int =
        trackedStashes.count { it.purpose == StashPurpose.APPROVED_DISCARD && it.repositoryPath == path }

    fun withSuccessfulCheckout(path: String): SwitchState =
        copy(successfulCheckouts = successfulCheckouts + path)

    fun checkoutSucceeded(path: String): Boolean = path in successfulCheckouts

    fun withInitializedSubmodule(path: String): SwitchState =
        copy(initializedSubmodules = initializedSubmodules + path)

    fun initializedSubmodulesSnapshot(): Set<String> = initializedSubmodules.toSet()

    fun retainedStashBackupsSnapshot(): Set<String> = retainedStashBackups.toSet()

    private fun copy(
        trackedStashes: List<TrackedStash> = this.trackedStashes,
        skippedPaths: Set<String> = this.skippedPaths,
        successfulCheckouts: Set<String> = this.successfulCheckouts,
        initializedSubmodules: Set<String> = this.initializedSubmodules,
        retainedStashBackups: Set<String> = this.retainedStashBackups,
    ): SwitchState = SwitchState(
        trackedStashes,
        skippedPaths,
        successfulCheckouts,
        initializedSubmodules,
        retainedStashBackups,
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
    val operationControl: OperationControl? = null,
    val progressHandle: ProgressHandle? = null,
    /** If true, missing submodule directories must be pre-approved before the switch starts. */
    val confirmBeforeInit: Boolean = false,
    /** Submodule paths the user approved for initialization before execution (no worker-time dialogs). */
    val preApprovedSubmoduleInit: Set<String> = emptySet(),
    /** File paths (per repo path, "." = main) the user approved for discard before switching. Empty when none. */
    val approvedCollisionDiscards: Map<String, Set<String>> = emptyMap(),
    /** Pre-switch repository identities used by later topology safety gates. */
    val checkpoint: Map<String, CheckpointEntry> = emptyMap(),
    /** Opaque id for one switch execution; scopes every stash message so a stale stash from an earlier operation can never be matched or restored. */
    val operationId: String,
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
