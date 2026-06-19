package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File
import java.nio.file.Path

/**
 * Pre-switch inspection: probes all repos in a preset and returns a [PreflightRow] per target.
 * Pure-JVM: no IntelliJ Platform dependencies. Cancellation via [CancellationHandle],
 * progress display via [onProgress] callback, error labels via [probeErrorSuffix].
 */
class SwitchPreflight(
    private val git: GitClient,
    private val probeErrorSuffix: String = "[probe error]",
) {
    /**
     * Iterates all targets in [preset], probing each for current branch, dirty status,
     * and whether the target branch exists locally or on origin.
     */
    fun probe(
        projectRoot: Path,
        preset: Preset,
        cancellationHandle: CancellationHandle? = null,
        onProgress: ((index: Int, total: Int, label: String) -> Unit)? = null,
    ): List<PreflightRow> {
        val targets = preset.targets()
        val total = targets.size.coerceAtLeast(1)
        return targets.mapIndexed { idx, t ->
            cancellationHandle?.checkCanceled()
            onProgress?.invoke(idx, total, if (t.path == ".") projectRoot.fileName.toString() else t.path)
            probeOne(projectRoot, t)
        }
    }

    @Suppress("TooGenericExceptionCaught") // safety probe: isolate per-repo git failures
    private fun probeOne(projectRoot: Path, target: RepoTarget): PreflightRow {
        val dir = if (target.path == ".") projectRoot.toFile()
                  else projectRoot.resolve(target.path).toFile()
        val label = if (target.path == ".") projectRoot.fileName.toString() else shortLabel(target.path)
        if (!dir.exists() || !git.isGitRepo(dir)) {
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
        } catch (e: java.util.concurrent.CancellationException) {
            throw e // cancellation must propagate, not become a warning row
        } catch (e: Exception) {
            // Fail closed per repo: one flaky git command must not abort the whole preflight.
            // All flags default to blocking/unknown so the user sees this repo as a warning.
            PreflightRow(
                label = "$label $probeErrorSuffix",
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

/** Returns the last path segment, stripping trailing `~`. Used for display labels. */
fun shortLabel(path: String): String =
    path.substringAfterLast('/').removeSuffix("~")
