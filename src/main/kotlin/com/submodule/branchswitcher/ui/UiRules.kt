package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

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
