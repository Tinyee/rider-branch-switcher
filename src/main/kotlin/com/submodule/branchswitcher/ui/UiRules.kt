package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow

enum class RepoStatusTone {
    NOT_INITIALIZED,
    MATCHED,
    MISMATCHED,
}

data class RepoStatusPresentation(
    val tone: RepoStatusTone,
    val tooltip: String,
)

fun repoStatusPresentation(
    path: String,
    currentBranch: String?,
    targetBranch: String,
    dirty: Boolean,
): RepoStatusPresentation {
    val tone = when {
        currentBranch == null -> RepoStatusTone.NOT_INITIALIZED
        currentBranch == targetBranch -> RepoStatusTone.MATCHED
        else -> RepoStatusTone.MISMATCHED
    }
    val baseTooltip = when (tone) {
        RepoStatusTone.NOT_INITIALIZED -> "$path: ${Bundle.msg("status.tooltip.not.init")}"
        RepoStatusTone.MATCHED -> Bundle.msg("status.tooltip.matched", path, currentBranch!!)
        RepoStatusTone.MISMATCHED -> Bundle.msg("status.tooltip.mismatch", path, currentBranch!!, targetBranch)
    }
    val tooltip = if (dirty) "$baseTooltip · ${Bundle.msg("status.tooltip.dirty")}" else baseTooltip
    return RepoStatusPresentation(tone, tooltip)
}

fun mainStatusText(currentBranch: String, targetBranch: String, dirty: Boolean): String? {
    return when {
        currentBranch != targetBranch -> Bundle.msg("preset.main.diff", currentBranch, targetBranch)
        dirty -> "${Bundle.msg("label.main.repo")} · ${Bundle.msg("status.tooltip.dirty")}"
        else -> null
    }
}

fun strategySummary(dirty: DirtyAction, fetch: Boolean, pull: Boolean, timeoutSeconds: Int): String {
    val dirtyLabel = when (dirty) {
        DirtyAction.Stash -> Bundle.msg("label.strategy.stash")
        DirtyAction.Skip -> Bundle.msg("label.strategy.skip")
        DirtyAction.Force -> Bundle.msg("label.strategy.force")
    }
    val parts = mutableListOf(dirtyLabel)
    if (fetch) parts += Bundle.msg("label.strategy.fetch")
    if (pull) parts += Bundle.msg("label.strategy.pull")
    parts += "${timeoutSeconds}s"
    return parts.joinToString(" · ")
}

/** True when [file] is a Unity `.meta` file (the target branch provides the tracked replacement). */
fun isCollisionFileMeta(file: String): Boolean = file.endsWith(".meta")

/**
 * Every derived output of the collision-discard decision for one [onlyMeta]/[autoMeta] state.
 * The dialog recomputes a single [CollisionDecision] per checkbox toggle and reads these
 * fields, so the summary, the confirm gate, the counts, and [noteFor] can never disagree
 * about which options were in effect.
 */
data class CollisionDecision(
    val onlyMeta: Boolean,
    val autoMeta: Boolean,
    val summary: String,
    val needsConfirm: Boolean,
    val total: Int,
    val metaCount: Int,
) {
    /** Note shown next to one colliding file under the options this decision was computed for. */
    fun noteFor(file: String): String = when {
        isCollisionFileMeta(file) && autoMeta -> Bundle.msg("dialog.collision.discard.meta.auto")
        isCollisionFileMeta(file) -> Bundle.msg("dialog.collision.discard.meta.safe")
        onlyMeta -> Bundle.msg("dialog.collision.discard.kept")
        else -> Bundle.msg("dialog.collision.discard.deleted")
    }
}

/**
 * Derives all collision-discard outputs from one [onlyMeta]/[autoMeta] state. The summary
 * always reflects "auto" while it is on, so toggling the two options in any order lands on
 * the same decision for the same end state. [collisions] is the full flattened list and the
 * counts are derived from it, so callers cannot pass counts that disagree with the files.
 */
fun collisionDecision(
    collisions: List<String>,
    onlyMeta: Boolean,
    autoMeta: Boolean,
): CollisionDecision {
    val metaCount = collisions.count { isCollisionFileMeta(it) }
    val approved = if (onlyMeta) metaCount else collisions.size
    val summary = when {
        onlyMeta && autoMeta -> Bundle.msg("dialog.collision.discard.summary.meta.auto", approved)
        onlyMeta -> Bundle.msg("dialog.collision.discard.summary.meta", approved)
        autoMeta -> Bundle.msg("dialog.collision.discard.summary.auto", approved, metaCount)
        else -> Bundle.msg("dialog.collision.discard.summary.all", approved)
    }
    // only-meta restricts the discard, so a kept (non-meta) file must not force the confirm
    // gate; only files this decision actually discards are judged against the auto rule.
    val discarded = if (onlyMeta) collisions.filter(::isCollisionFileMeta) else collisions
    return CollisionDecision(
        onlyMeta = onlyMeta,
        autoMeta = autoMeta,
        summary = summary,
        needsConfirm = discarded.any { !(autoMeta && isCollisionFileMeta(it)) },
        total = collisions.size,
        metaCount = metaCount,
    )
}

/** Placeholder shown in a branch combo while the real branch list is loading. */
internal val LOADING_BRANCH: String = Bundle.msg("status.loading")

/**
 * Normalizes the branch choices for one combo: deduplicates and drops blank or
 * still-loading entries, and prepends the currently checked-out branch when it is
 * not among the choices so the user's current state stays selectable.
 */
internal fun mergeBranchChoices(current: String, branches: List<String>): List<String> {
    val normalized = branches
        .filter { it.isNotBlank() && it != LOADING_BRANCH }
        .distinct()
    return if (current.isNotBlank() && current != LOADING_BRANCH && current !in normalized) {
        listOf(current) + normalized
    } else {
        normalized
    }
}

/** Why a "create preset from current state" is unavailable, or null when it is available. */
internal enum class CurrentStatePresetBlockReason {
    MAIN_BRANCH_UNAVAILABLE,
    INCOMPLETE_REPOSITORIES,
}

internal fun currentStatePresetBlockReason(
    mainBranch: String?,
    skippedRepositories: List<String>,
): CurrentStatePresetBlockReason? = when {
    mainBranch.isNullOrEmpty() -> CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE
    skippedRepositories.isNotEmpty() -> CurrentStatePresetBlockReason.INCOMPLETE_REPOSITORIES
    else -> null
}

/**
 * Derives the untracked files the user approved for discard from the preview rows.
 * .meta collisions are always included (the target branch provides the tracked replacement); any other file only when
 * the user did not restrict the discard to .meta. The preview's Cancel already aborted
 * the switch on decline, so no null result exists here.
 */
internal fun resolveCollisionDiscards(probeResult: List<PreflightRow>, onlyMeta: Boolean): Map<String, Set<String>> =
    probeResult
        .filter { it.untrackedCollisions.isNotEmpty() }
        .associate { row ->
            row.path to if (onlyMeta) {
                row.untrackedCollisions.filter { isCollisionFileMeta(it) }.toSet()
            } else {
                row.untrackedCollisions
            }
        }
        .filterValues { it.isNotEmpty() }

/** Header text for the panel's main-repo status line, including the dirty suffix. */
internal fun mainBranchStatusText(main: String, dirty: Boolean): String =
    "${Bundle.msg("label.main.branch")} $main" + if (dirty) " · ${Bundle.msg("status.tooltip.dirty")}" else ""

// ── Preview-table cell decisions ───────────────────────────
/** Tone for one preview-table cell; the dialog maps it to a theme color. */
internal enum class PreviewCellTone { NORMAL, MUTED, WARN, ACCENT }

/** Column-1 text: current branch, missing directory, or the probe error. */
internal fun currentBranchCellText(row: PreflightRow): String {
    // ProbeError is a cross-module val, so Kotlin cannot smart-cast it; capture to a
    // local (stable) value instead of relying on a property smart cast.
    row.probeError?.let { return it }
    return if (!row.exists) {
        Bundle.msg("status.missing.dir")
    } else {
        row.current ?: Bundle.msg("status.detached")
    }
}

internal fun currentBranchCellTone(row: PreflightRow): PreviewCellTone = when {
    !row.exists || row.probeError != null -> PreviewCellTone.WARN
    !row.needsSwitch -> PreviewCellTone.MUTED
    else -> PreviewCellTone.NORMAL
}

internal fun targetCellTone(row: PreflightRow): PreviewCellTone = when {
    row.branchMissing -> PreviewCellTone.WARN
    row.needsSwitch -> PreviewCellTone.ACCENT
    else -> PreviewCellTone.MUTED
}

/** Column-3 text: clean / dirty count / collision count / unknown. */
internal fun dirtyCellText(row: PreflightRow): String = when {
    !row.exists -> "—"
    row.dirtyCount < 0 -> "?"
    row.dirtyCount == 0 -> Bundle.msg("status.clean")
    row.untrackedCollisions.isNotEmpty() -> Bundle.msg(
        "status.file.count.collision",
        row.dirtyCount,
        row.untrackedCollisions.size,
    )
    else -> Bundle.msg("status.file.count", row.dirtyCount)
}

internal fun dirtyCellTone(row: PreflightRow): PreviewCellTone = when {
    !row.exists || row.dirtyCount < 0 -> PreviewCellTone.MUTED
    row.dirtyCount == 0 -> PreviewCellTone.MUTED
    else -> PreviewCellTone.WARN
}

/** Column-4 text: local-only / remote-only / both / none. */
internal fun sourceCellText(row: PreflightRow): String = when {
    !row.exists -> "—"
    row.hasLocal && row.hasRemote -> Bundle.msg("status.both")
    row.hasLocal -> Bundle.msg("status.local.only")
    row.hasRemote -> Bundle.msg("status.remote.only")
    else -> Bundle.msg("status.none")
}

internal fun sourceCellTone(row: PreflightRow): PreviewCellTone = when {
    !row.exists -> PreviewCellTone.MUTED
    row.branchMissing -> PreviewCellTone.WARN
    else -> PreviewCellTone.NORMAL
}
