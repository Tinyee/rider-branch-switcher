package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/**
 * True when [relativePath] resolves inside [directory] and is not the repository root
 * itself, so a deletion can never escape the worktree. [resolveGitDir] guards the
 * per-target root, but each approved file path still needs its own containment check.
 */
internal fun safeRepoRelativePath(relativePath: String, directory: File): Boolean {
    val root = directory.canonicalFile
    val target = File(directory, relativePath).canonicalFile
    return target != root && target.path.startsWith("${root.path}${File.separator}")
}

/**
 * Returns the subset of the user-approved collision files for [target] that is STILL a
 * provable checkout collision against [ref] (a frozen 40-hex revision, or a branch name the
 * client resolves the same way): still in that tree, still untracked, still an existing file,
 * and still inside the repository.
 *
 * Fail-safe: when the target tree or untracked set cannot be read (a [GitQueryException])
 * nothing is returned. An approved file is only discarded when the collision is provable at
 * execution time, so a target that moved after preflight keeps its files.
 */
internal fun collidingApproved(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    ref: String,
): Set<String> {
    val approved = context.approvedCollisionDiscards[target.path].orEmpty()
    if (approved.isEmpty()) return emptySet()
    val stillInTree = try {
        context.git.targetBranchMatches(directory, ref, approved.toList()).toSet()
    } catch (error: GitQueryException) {
        context.log.warn(
            "[discard] cannot revalidate target tree for ${target.path}: ${error.result.diagnostic()}",
        )
        return emptySet()
    }
    val untracked = try {
        context.git.untrackedFiles(directory).toSet()
    } catch (error: GitQueryException) {
        context.log.warn(
            "[discard] cannot read untracked files for ${target.path}: ${error.result.diagnostic()}",
        )
        return emptySet()
    }
    return approved.filter { path ->
        safeRepoRelativePath(path, directory) &&
            path in stillInTree &&
            path in untracked &&
            File(directory, path).isFile
    }.toSet()
}

/** Deletes the approved files that are still a provable collision against [ref]. */
internal fun discardCollidingApproved(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    ref: String,
    stage: OperationStage,
): List<OperationIssue> {
    val stillColliding = collidingApproved(context, target, directory, ref)
    if (stillColliding.isEmpty()) return emptyList()
    return discardApprovedFiles(directory, stillColliding, context.log, stage, target.path)
}
