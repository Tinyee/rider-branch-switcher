package com.submodule.branchswitcher.switch

import com.intellij.openapi.progress.ProgressIndicator
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File
import java.nio.file.Path

/**
 * Pre-switch inspection: probes all repos in a preset and returns a [PreflightRow] per target.
 * Used by [SwitchPreviewDialog] to show what will change before executing the switch.
 */
class SwitchPreflight(
    private val git: GitClient,
) {
    /**
     * Iterates all targets in [preset], probing each for current branch, dirty status,
     * and whether the target branch exists locally or on origin.
     */
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

    @Suppress("TooGenericExceptionCaught") // safety probe: isolate per-repo git failures
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
        return try {
            PreflightRow(
                label = label,
                path = target.path,
                target = target.branch,
                exists = true,
                current = git.currentBranch(dir),
                dirtyCount = git.dirtyFileCount(dir),
                hasLocal = git.localBranchExists(dir, target.branch),
                hasRemote = git.remoteBranchExists(dir, target.branch),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancellation must propagate, not become a warning row
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e // same for IntelliJ modal cancellation
        } catch (e: Exception) {
            // Fail closed per repo: one flaky git command must not abort the whole preflight.
            // All flags default to blocking/unknown so the user sees this repo as a warning.
            PreflightRow(
                label = "$label ${com.submodule.branchswitcher.Bundle.msg("preflight.probe.error.suffix")}",
                path = target.path,
                target = target.branch,
                exists = true,
                current = null,
                dirtyCount = -1,
                hasLocal = false,
                hasRemote = false,
            )
        }
    }

}
