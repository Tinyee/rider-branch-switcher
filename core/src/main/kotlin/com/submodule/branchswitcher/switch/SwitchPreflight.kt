package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchPreflightGitClient
import com.submodule.branchswitcher.git.SwitchPreflightBatchGitClient
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File
import java.nio.file.Path

/**
 * Pre-switch inspection: probes all repos in a preset and returns a [PreflightRow] per target.
 * Pure-JVM: no IntelliJ Platform dependencies. Cancellation via [OperationControl],
 * progress display via [onProgress] callback, error labels via [probeErrorSuffix].
 */
class SwitchPreflight(
    private val git: SwitchPreflightGitClient,
    private val probeErrorSuffix: String = "[probe error]",
    private val onProbeFailure: (path: String, error: Exception) -> Unit = { _, _ -> },
) {
    /**
     * Iterates all targets in [preset], probing each for current branch, dirty status,
     * and whether the target branch exists locally or on origin.
     */
    fun probe(
        projectRoot: Path,
        preset: Preset,
        operationControl: OperationControl? = null,
        onProgress: ((index: Int, total: Int, label: String) -> Unit)? = null,
    ): List<PreflightRow> {
        val targets = preset.targets()
        val total = targets.size.coerceAtLeast(1)
        return targets.mapIndexed { idx, t ->
            operationControl?.checkCancelled()
            onProgress?.invoke(idx, total, displayLabel(projectRoot, t.path))
            probeOne(projectRoot, t)
        }
    }

    @Suppress("TooGenericExceptionCaught") // safety probe: isolate per-repo git failures
    private fun probeOne(projectRoot: Path, target: RepoTarget): PreflightRow {
        val dir = resolveGitDir(projectRoot, target.path)
        val label = if (target.path == ".") projectRoot.fileName.toString() else shortLabel(target.path)
        if (!dir.exists()) return notGitRepoRow(label, target)
        return try {
            val inspection = if (git is SwitchPreflightBatchGitClient) {
                git.inspectPreflight(dir, setOf(target.branch))
            } else {
                null
            }
            if (inspection?.isGitRepository == false || (inspection == null && !git.isGitRepo(dir))) {
                return notGitRepoRow(label, target)
            }
            val row = PreflightRow(
                label = label,
                path = target.path,
                target = target.branch,
                exists = true,
                current = if (inspection != null) inspection.currentBranch else git.currentBranch(dir),
                dirtyCount = inspection?.dirtyFileCount ?: git.dirtyFileCount(dir),
                hasLocal = inspection?.localBranches?.contains(target.branch)
                    ?: git.localBranchExists(dir, target.branch),
                hasRemote = inspection?.remoteBranches?.contains(target.branch)
                    ?: git.remoteBranchExists(dir, target.branch),
            )
            // Only a repo being switched can collide, and a missing branch never reaches a
            // checkout, so both gates keep the extra queries off the common (clean/on-target)
            // preflight path.
            if (row.needsSwitch && (row.hasLocal || row.hasRemote)) {
                row.copy(untrackedCollisions = computeCollisions(dir, target.branch))
            } else {
                row
            }
        } catch (e: OperationCancelledException) {
            // A cancelled probe is a user cancel: it must abort the preflight, not degrade
            // to a fail-closed warning row.
            throw e
        } catch (e: Exception) {
            onProbeFailure(target.path, e)
            // Fail closed per repo: one flaky git command must not abort the whole preflight.
            // All flags default to blocking/unknown so the user sees this repo as a warning.
            probeErrorRow(label, target, e)
        }
    }

    /** Untracked ∩ target-branch-tracked = files git checkout would refuse to overwrite. */
    private fun computeCollisions(dir: File, branch: String): Set<String> {
        val untracked = git.untrackedFiles(dir)
        if (untracked.isEmpty()) return emptySet()
        return git.targetBranchMatches(dir, branch, untracked).toSet()
    }

    /** Row for a target that is missing or is not a git repository: absent, every state flag unknown. */
    private fun notGitRepoRow(label: String, target: RepoTarget) = PreflightRow(
        label = label,
        path = target.path,
        target = target.branch,
        exists = false,
        current = null,
        dirtyCount = -1,
        hasLocal = false,
        hasRemote = false,
    )

    /** Fail-closed row for a repo whose probe errored: exists, but every state flag stays unknown. */
    private fun probeErrorRow(label: String, target: RepoTarget, error: Exception) = PreflightRow(
        label = "$label $probeErrorSuffix",
        path = target.path,
        target = target.branch,
        exists = true,
        current = null,
        dirtyCount = -1,
        hasLocal = false,
        hasRemote = false,
        probeError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(300),
    )

}

/** Returns the last path segment, stripping trailing `~`. Used for display labels. */
fun shortLabel(path: String): String =
    path.substringAfterLast('/').removeSuffix("~")
