package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Files

/**
 * Deletes untracked files the user approved for discard before the switch's checkout
 * attempts them, so git does not refuse to overwrite them.
 *
 * Runs before [DirtyHandlingStep]: files discarded here are never swept into the stash
 * (`git stash push -u` includes untracked), which would otherwise re-apply them into a
 * fresh collision with the newly checked-out tracked versions during restore.
 */
class DiscardUntrackedCollisionStep : SwitchStep {
    override val name = "discard untracked collisions"
    override val stage = OperationStage.DIRTY_HANDLING

    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val approved = context.approvedCollisionDiscards
        if (approved.isEmpty()) return StepExecution(StepResult.Success, state)
        val issues = mutableListOf<OperationIssue>()
        for ((repoPath, files) in approved) {
            if (files.isEmpty()) continue
            val targetBranch = context.preset.targets().find { it.path == repoPath }?.branch ?: continue
            // The repo is already on the target branch (the branch may have changed since
            // the user approved the discard): checkout will not run, so deleting approved
            // files would be needless data loss.
            if (context.checkpoint[repoPath]?.branch == targetBranch) continue
            val dir = resolveGitDir(context.projectRoot, repoPath)
            if (!dir.exists()) continue
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
