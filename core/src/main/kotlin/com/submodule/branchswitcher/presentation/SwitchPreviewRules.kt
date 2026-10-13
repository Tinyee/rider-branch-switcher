package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.ResolvedSwitchRequest

/** Warning condition for switching without stashing — pure rule, no UI dependency. */
fun shouldShowForceWarning(
    request: ResolvedSwitchRequest,
    rows: List<PreflightRow>,
): Boolean = request.options.dirty == DirtyAction.Force
    && rows.any { it.exists && it.dirtyCount != 0 }

/**
 * The repository counts a preview's summary line is built from.
 *
 * Each count is a distinct predicate over the same rows, and they are not
 * interchangeable: [toSwitch] excludes probe errors while [missingBranch] does not,
 * because a failed probe is unknown state rather than a known-missing branch, and
 * counting it in either direction would state something the probe never established.
 */
data class SwitchPreviewCounts(
    val total: Int,
    val toSwitch: Int,
    val noChange: Int,
    val missingBranch: Int,
    val missingDir: Int,
    val dirty: Int,
)

/** Derives every count the summary line shows from one pass over [rows]. */
fun switchPreviewCounts(rows: List<PreflightRow>): SwitchPreviewCounts = SwitchPreviewCounts(
    total = rows.size,
    // Probe errors are unknown state: neither a repo we know needs switching nor a
    // missing branch. Exclude them from the count so the summary stays truthful.
    toSwitch = rows.count { it.exists && it.needsSwitch && !it.branchMissing && it.probeError == null },
    noChange = rows.count { it.exists && !it.needsSwitch },
    missingBranch = rows.count { it.branchMissing },
    missingDir = rows.count { !it.exists },
    dirty = rows.count { it.dirtyCount > 0 },
)

/**
 * One line of the summary, as data rather than as rendered text: this module is pure JVM
 * and cannot reach the bundle. The UI renders each part through an exhaustive `when`, so
 * adding a part is a compile error there rather than a line that silently goes missing.
 *
 * [Repos] and [ToSwitch] always appear. The rest appear only when their count is non-zero,
 * so a zero never reads as a reported fact.
 */
sealed interface SwitchPreviewSummaryPart {
    data class Repos(val count: Int) : SwitchPreviewSummaryPart
    data class ToSwitch(val count: Int) : SwitchPreviewSummaryPart
    data class NoChange(val count: Int) : SwitchPreviewSummaryPart
    data class Dirty(val count: Int) : SwitchPreviewSummaryPart
    data class MissingBranch(val count: Int) : SwitchPreviewSummaryPart
    data class MissingDir(val count: Int) : SwitchPreviewSummaryPart
}

/** The summary parts to render, in the order they read, omitting the optional zeroes. */
fun switchPreviewSummaryParts(counts: SwitchPreviewCounts): List<SwitchPreviewSummaryPart> =
    buildList {
        add(SwitchPreviewSummaryPart.Repos(counts.total))
        add(SwitchPreviewSummaryPart.ToSwitch(counts.toSwitch))
        if (counts.noChange > 0) add(SwitchPreviewSummaryPart.NoChange(counts.noChange))
        if (counts.dirty > 0) add(SwitchPreviewSummaryPart.Dirty(counts.dirty))
        if (counts.missingBranch > 0) add(SwitchPreviewSummaryPart.MissingBranch(counts.missingBranch))
        if (counts.missingDir > 0) add(SwitchPreviewSummaryPart.MissingDir(counts.missingDir))
    }

/**
 * The OK button's message key: a switch that discards a non-auto-approved collision has
 * to say so on the button, and every other switch keeps the plain title.
 *
 * Both the dialog's initial paint and its live updates go through here. They previously
 * each spelled the pair of keys out, agreeing only because both happened to pass the same
 * arguments.
 */
fun switchOkButtonKey(needsDiscardConfirm: Boolean): String =
    if (needsDiscardConfirm) "dialog.collision.discard.ok" else "dialog.switch.title"
