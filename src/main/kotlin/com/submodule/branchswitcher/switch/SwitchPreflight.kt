package com.submodule.branchswitcher.switch

import com.intellij.openapi.progress.ProgressIndicator
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File
import java.nio.file.Path

class SwitchPreflight(
    private val git: GitClient,
) {
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
            indicator?.text2 = if (t.path == ".") projectRoot.fileName.toString() else t.path
            probeOne(projectRoot, t)
        }
    }

    private fun probeOne(projectRoot: Path, target: RepoTarget): PreflightRow {
        val dir = if (target.path == ".") projectRoot.toFile()
                  else projectRoot.resolve(target.path).toFile()
        val label = if (target.path == ".") projectRoot.fileName.toString() else shortLabel(target.path)
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
            current = git.currentBranch(dir),
            dirtyCount = git.dirtyFileCount(dir),
            hasLocal = git.localBranchExists(dir, target.branch),
            hasRemote = git.remoteBranchExists(dir, target.branch),
        )
    }

    private fun shortLabel(path: String): String {
        if (!path.contains("/")) return path
        return path.substringAfterLast('/').removeSuffix("~")
    }
}
