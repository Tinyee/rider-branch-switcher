package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.DirtyAction
import java.io.File
import java.nio.file.Files

/**
 * Deletes untracked files the user approved for discard before the switch's checkout
 * attempts them, so git does not refuse to overwrite them.
 *
 * Runs before [DirtyHandlingStep] and only acts on repositories the Stash strategy will
 * actually stash: files discarded here are never swept into that stash (`git stash push
 * -u` includes untracked), which would otherwise re-apply them into a fresh collision
 * with the newly checked-out tracked versions during restore. Every other approved
 * repository is discarded just-in-time in [BranchCheckout], right before its checkout
 * write.
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
        val issues = mutableListOf<OperationIssue>()
        for ((repoPath, files) in approved) {
            context.cancellationHandle?.checkCanceled()
            if (files.isEmpty()) continue
            val targetBranch = context.preset.targets().find { it.path == repoPath }?.branch ?: continue
            // The repo is already on the target branch (the branch may have changed since
            // the user approved the discard): checkout will not run, so deleting approved
            // files would be needless data loss.
            if (context.checkpoint[repoPath]?.branch == targetBranch) continue
            val dir = resolveGitDir(context.projectRoot, repoPath)
            if (!dir.exists()) continue
            // Only delete now when the Stash strategy is about to sweep the files into a
            // WIP backup (`git stash push -u` includes untracked): deleting here keeps the
            // approved collision files out of the stash so the end-of-switch restore cannot
            // re-apply them onto the freshly checked-out tracked versions. That ordering is
            // a trade-off: a dirty+Stash repo is deleted up-front even if a later gate (a
            // failed main checkout, a topology change) then skips it, and those files are
            // gone without its checkout running. Repositories that are never stashed (Force,
            // or clean enough) are instead deleted just-in-time in BranchCheckout, so a
            // downstream skip preserves their approved files.
            if (context.options.dirty != DirtyAction.Stash || !context.git.isDirty(dir)) continue
            issues += discardApprovedFiles(dir, files, context.log, stage, repoPath)
        }
        return StepExecution(issues.toStepResult(), state)
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
