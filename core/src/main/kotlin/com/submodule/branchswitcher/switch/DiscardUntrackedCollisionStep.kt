package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File
import java.nio.file.Files

/**
 * Deletes the main repository's untracked files the user approved for discard before the
 * switch's checkout attempts them, so git does not refuse to overwrite them.
 *
 * Runs after [FetchStep] and before [DirtyHandlingStep]: the fetch refreshes the target ref,
 * the discard revalidates the approved set against the frozen revision (so a target that
 * moved after preflight keeps its files), and deletion precedes the main `git stash push -u`
 * so the approved files are never swept into the WIP backup (which would otherwise re-apply
 * them onto the freshly checked-out tracked versions during restore).
 *
 * Submodule discards are handled inside [SubmoduleTreeStep], after the topology write gate
 * confirms each path is registered — never here, where the new topology is not yet known.
 */
class DiscardUntrackedCollisionStep : SwitchStep {
    override val name = "discard untracked collisions"
    override val stage = OperationStage.DIRTY_HANDLING

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val approved = context.approvedCollisionDiscards
        if (approved.isEmpty()) return StepExecution(StepResult.Success, state)
        // Untracked collision files count toward the dirty count, so under the Skip
        // strategy every repo with approved discards is skipped before checkout: deleting
        // them would be unrecoverable loss with no switch happening.
        if (context.options.dirty == DirtyAction.Skip) return StepExecution(StepResult.Success, state)

        val mainApproved = approved["."].orEmpty()
        if (mainApproved.isEmpty()) return StepExecution(StepResult.Success, state)

        val issues = mutableListOf<OperationIssue>()
        context.cancellationHandle?.checkCanceled()
        val target = RepoTarget(".", context.preset.main)
        // The repo is already on the target branch (the branch may have changed since the
        // user approved the discard): checkout will not run, so deleting approved files
        // would be needless data loss.
        if (context.checkpoint["."]?.branch == target.branch) {
            return StepExecution(StepResult.Success, state)
        }
        val dir = resolveGitDir(context.projectRoot, ".")
        if (!dir.exists()) return StepExecution(StepResult.Success, state)

        // Freeze the revision the checkout will operate on (resolved after the main fetch),
        // so revalidation and checkout provably use the same tree.
        val frozenSha = try {
            context.git.resolveTargetRevision(dir, target.branch)
        } catch (error: GitQueryException) {
            context.log.warn("[discard] cannot resolve target revision for main: ${error.result.diagnostic()}")
            null
        }
        var nextState = state
        if (frozenSha != null) {
            nextState = nextState.withFrozenTargetSha(".", frozenSha)
            // Only delete now when the Stash strategy is about to sweep the worktree into a
            // WIP backup (`git stash push -u` includes untracked); Force and clean repos are
            // discarded just-in-time in BranchCheckout instead, so a downstream skip keeps them.
            if (context.options.dirty == DirtyAction.Stash && context.git.isDirty(dir)) {
                issues += discardCollidingApproved(context, target, dir, frozenSha, stage)
            }
        }
        return StepExecution(issues.toStepResult(), nextState)
    }
}

/**
 * Deletes approved untracked files under [dir], best-effort: missing paths and directories
 * are skipped (they may already be gone, and only files were ever detected as collisions).
 * Returns any failures as warning issues; callers decide whether to fold them into a step
 * result or just log them.
 */
@Suppress("TooGenericExceptionCaught") // one delete failure must not abort the whole discard
internal fun discardApprovedFiles(
    dir: File,
    files: Set<String>,
    log: AppLogger,
    stage: OperationStage,
    repositoryPath: String,
): List<OperationIssue> {
    if (files.isEmpty()) return emptyList()
    val issues = mutableListOf<OperationIssue>()
    for (path in files) {
        val target = File(dir, path)
        if (!target.exists() || !target.isFile) continue
        try {
            Files.delete(target.toPath())
            log.info("[discard] deleted $path in ${dir.path}")
        } catch (e: Exception) {
            issues += OperationIssue(
                stage,
                OperationIssueCode.UNTRACKED_DISCARD_FAILED,
                repositoryPath,
                severity = OperationIssueSeverity.WARNING,
                diagnostic = "$path: ${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
        }
    }
    return issues
}
