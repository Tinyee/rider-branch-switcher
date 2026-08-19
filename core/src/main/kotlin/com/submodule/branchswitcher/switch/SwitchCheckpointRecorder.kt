package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.git.resolveHeadAndBranch
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.diagnosticFingerprint
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
            val label = displayLabel(projectRoot, target.path)
            val identity = git.repositoryIdentity(dir)
            val registration = topology.byPath[target.path]
            val expectedGitDirectory = expectedSubmoduleGitDirectory(
                projectRoot.toFile(),
                registration,
                git,
            )
            // The main repository is always checkpointable regardless of topology. A target the
            // current main does not register reports no superproject from git, even though it may
            // be a canonical leftover submodule the preset's main branch will re-register during
            // this switch. Only a standalone `.git` inside the worktree (or an otherwise
            // non-canonical git directory) stays fail-closed.
            val unassociated = when {
                target.path == "." -> false
                registration == null -> !isCanonicalLeftoverGitDirectory(dir, identity)
                else -> isUnassociatedSubmoduleWorktree(
                    projectRoot.toFile(),
                    target.path,
                    dir,
                    identity,
                    expectedGitDirectory,
                )
            }
            if (unassociated) {
                log.error(
                    "[checkpoint] $label: repository is not associated with its superproject; " +
                        "actualGitDir=${identity?.gitDirectory}, expectedGitDir=$expectedGitDirectory, " +
                        "superproject=${identity?.superprojectRoot}",
                )
                return null
            }
            // HEAD SHA and branch come from one git invocation when the client
            // supports it, so a concurrent checkout cannot pair the SHA with the
            // wrong branch name in the rollback checkpoint.
            val resolved = git.resolveHeadAndBranch(dir)
            if (resolved == null) {
                log.error("[checkpoint] $label: unable to read HEAD")
                return null
            }
            // resolveHeadAndBranch only returns a result when HEAD is resolvable,
            // so its SHA is guaranteed non-null here.
            val sha = resolved.sha!!
            val branch = resolved.branch
            val declaredUrl = registration?.url
            checkpoint[target.path] = CheckpointEntry(
                sha,
                branch,
                identity?.gitDirectory,
                declaredUrl,
                registeredAtCheckpoint = registration != null,
            )
            log.info(
                "[checkpoint] $label: branch=${branch ?: "(detached)"}, head=${sha.take(12)}, " +
                    "gitDir=${identity?.gitDirectory ?: "unknown"}, " +
                    "declaredId=${diagnosticFingerprint(declaredUrl)}",
            )
        }
        return checkpoint
    }
}
