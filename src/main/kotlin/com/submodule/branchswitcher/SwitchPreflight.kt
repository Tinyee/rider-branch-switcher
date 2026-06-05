package com.submodule.branchswitcher

import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.file.Path

data class PreflightRow(
    val label: String,
    val path: String,
    val target: String,
    val exists: Boolean,
    val current: String?,
    val dirtyCount: Int,
    val hasLocal: Boolean,
    val hasRemote: Boolean,
) {
    val isMain: Boolean get() = path == "."
    val needsSwitch: Boolean get() = exists && current != target
    val branchMissing: Boolean get() = exists && !hasLocal && !hasRemote
}

object SwitchPreflight {
    fun probe(
        projectRoot: Path,
        preset: Preset,
        indicator: ProgressIndicator? = null,
    ): List<PreflightRow> {
        val targets = preset.targets()
        val total = targets.size.coerceAtLeast(1)
        return targets.mapIndexed { idx, t ->
            indicator?.checkCanceled()
            indicator?.fraction = idx.toDouble() / total
            indicator?.text2 = if (t.path == ".") "<main>" else t.path
            probeOne(projectRoot, t)
        }
    }

    private fun probeOne(projectRoot: Path, target: RepoTarget): PreflightRow {
        val dir = if (target.path == ".") projectRoot.toFile()
                  else projectRoot.resolve(target.path).toFile()
        val label = if (target.path == ".") "<main>" else shortLabel(target.path)
        if (!dir.exists() || !isGitRepo(dir)) {
            return PreflightRow(
                label = label,
                path = target.path,
                target = target.branch,
                exists = false,
                current = null,
                dirtyCount = -1,
                hasLocal = false,
                hasRemote = false,
            )
        }
        return PreflightRow(
            label = label,
            path = target.path,
            target = target.branch,
            exists = true,
            current = GitOps.currentBranch(dir),
            dirtyCount = GitOps.dirtyFileCount(dir),
            hasLocal = GitOps.localBranchExists(dir, target.branch),
            hasRemote = GitOps.remoteBranchExists(dir, target.branch),
        )
    }

    private fun isGitRepo(dir: File): Boolean {
        val dot = File(dir, ".git")
        return dot.exists()
    }

    private fun shortLabel(path: String): String {
        if (!path.contains("/")) return path
        return path.substringAfterLast('/').removeSuffix("~")
    }
}
