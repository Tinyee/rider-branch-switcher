package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import java.nio.file.Path

/**
 * Captures the repository state required to undo a switch.
 *
 * Missing submodule directories are intentionally excluded: they have no state to restore
 * and may be initialized later in the switch pipeline.
 */
internal class SwitchCheckpointRecorder(
    private val projectRoot: Path,
    private val log: AppLogger,
    private val git: SwitchGitClient,
) {
    /**
     * Returns null when an existing Git repository cannot be checkpointed.
     */
    fun record(preset: Preset): Map<String, CheckpointEntry>? {
        val checkpoint = LinkedHashMap<String, CheckpointEntry>()
        val topology = git.loadSubmoduleTopology(projectRoot.toFile())
        for (target in preset.targets()) {
            val dir = resolveGitDir(projectRoot, target.path)
            if (!dir.exists() || !git.isGitRepo(dir)) continue
            val label = if (target.path == ".") projectRoot.fileName.toString() else target.path
            val identity = git.repositoryIdentity(dir)
            if (isUnassociatedSubmoduleWorktree(
                    projectRoot.toFile(),
                    target.path,
                    dir,
                    identity,
                    expectedSubmoduleGitDirectory(projectRoot.toFile(), topology.byPath[target.path], git),
                )
            ) {
                log.error("[checkpoint] $label: repository is not associated with its superproject")
                return null
            }
            val sha = git.revParseHead(dir)
            if (sha == null) {
                log.error("[checkpoint] $label: unable to read HEAD")
                return null
            }
            checkpoint[target.path] = CheckpointEntry(
                sha,
                git.currentBranch(dir),
                identity?.gitDirectory,
                git.remoteUrl(dir),
            )
        }
        return checkpoint
    }
}
