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

/**
 * The dialog's three width constants, in raw pixels. The UI scales them by the IDE's
 * font-derived scale factor; keeping the arithmetic here means the rules below are
 * testable without a UI toolkit.
 */
object SwitchPreviewMetrics {
    const val DIALOG_WIDTH = 720
    const val MAX_TABLE_WIDTH = 1080

    /** A few px under [DIALOG_WIDTH] so the exact-fit case never shows a horizontal scrollbar. */
    const val TABLE_FILL_INSET = 4

    /** Breathing room added to the packed width when sizing the scroll pane. */
    const val SCROLL_PANE_PADDING = 8

    /** Rows past this count scroll instead of growing the dialog. */
    const val MAX_VISIBLE_ROWS = 8

    /** Chrome above and below the visible rows (header plus horizontal scrollbar). */
    const val VERTICAL_CHROME = 56
}

/** The table width the dialog asks for: fill the dialog, but never past the cap. */
fun switchPreviewTableWidth(
    packedWidth: Int,
    scaledDialogWidth: Int,
    scaledMaxTableWidth: Int,
    padding: Int,
): Int = maxOf(scaledDialogWidth, minOf(packedWidth + padding, scaledMaxTableWidth))

/** The scroll pane height for [rowCount] rows, bounded so a long preset does not stretch it. */
fun switchPreviewTableHeight(rowCount: Int, rowHeight: Int, verticalChrome: Int): Int =
    (rowCount.coerceAtMost(SwitchPreviewMetrics.MAX_VISIBLE_ROWS) * rowHeight) + verticalChrome

/**
 * Grows [packedWidths] to sum to exactly [targetWidth], spreading the slack in proportion
 * to each column's packed width.
 *
 * The last column takes the remainder rather than its proportional share, so the result
 * sums to [targetWidth] exactly. Without that, integer division would leave the table a
 * few pixels short of the dialog and leave a visible gap on the right.
 *
 * [packedWidths] must be non-empty and sum to a positive value; the caller packs every
 * column to at least a minimum width first.
 */
fun expandColumnsProportionally(packedWidths: List<Int>, targetWidth: Int): List<Int> {
    require(packedWidths.isNotEmpty()) { "no columns to expand" }
    val packedWidth = packedWidths.sum()
    require(packedWidth > 0) { "packed widths must be positive, was $packedWidth" }
    val slack = targetWidth - packedWidth
    var distributed = 0
    return packedWidths.mapIndexed { index, width ->
        val share = if (index == packedWidths.lastIndex) {
            slack - distributed
        } else {
            slack * width / packedWidth
        }
        distributed += share
        width + share
    }
}
