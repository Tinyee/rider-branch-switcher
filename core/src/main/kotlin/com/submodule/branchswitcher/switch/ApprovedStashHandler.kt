package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Prefix of every approved-discard stash message; used to locate them by message. */
internal const val APPROVED_DISCARD_MESSAGE_PREFIX = "branch-switcher: approved-discard "

/** Result of one approved-collision isolation attempt. */
internal data class ApprovedStashOutcome(
    val state: SwitchState,
    val created: Boolean,
    val issue: OperationIssue? = null,
)

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
 * Returns the approved collision paths for [target] that are STILL a checkout collision:
 * still in the target branch's tree, still untracked, still an existing file, and still
 * inside the repository. Fail-safe: when the target tree or untracked set cannot be read,
 * nothing is returned — an approved file is only isolated when the collision is provable.
 */
internal fun approvedCollisionPaths(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
): Set<String> {
    val approved = context.approvedCollisionDiscards[target.path].orEmpty()
    if (approved.isEmpty()) return emptySet()
    val stillInTree = try {
        context.git.targetBranchMatches(directory, target.branch, approved.toList()).toSet()
    } catch (error: GitQueryException) {
        context.log.warn("[discard] cannot revalidate target tree for ${target.path}: ${error.result.diagnostic()}")
        return emptySet()
    }
    val untracked = try {
        context.git.untrackedFiles(directory).toSet()
    } catch (error: GitQueryException) {
        context.log.warn("[discard] cannot read untracked files for ${target.path}: ${error.result.diagnostic()}")
        return emptySet()
    }
    return approved.filter { path ->
        safeRepoRelativePath(path, directory) &&
            path in stillInTree &&
            path in untracked &&
            File(directory, path).isFile
    }.toSet()
}

/**
 * Isolates [target]'s still-colliding approved files into ONE path-scoped stash per round
 * (`git stash push -u -m <opaque message> -- <paths>`), so the checkout cannot refuse to
 * overwrite them and the WIP stash never sweeps them into the backup. Records the
 * APPROVED_DISCARD stash in state. The message carries only the repo path and round — never
 * file paths (they must not surface in the user-visible stash reflog).
 *
 * When the push creates no stash (a listed path is already gone or tracked), the paths are
 * re-validated: if they are no longer collisions the isolation is a no-op and the switch
 * proceeds; if they are STILL approved untracked collisions the switch fails closed rather
 * than pretending they were discarded.
 */
internal fun stashApprovedCollisions(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    state: SwitchState,
    issues: MutableList<OperationIssue>,
    stage: OperationStage,
): ApprovedStashOutcome {
    val paths = approvedCollisionPaths(context, target, directory)
    if (paths.isEmpty()) return ApprovedStashOutcome(state, created = false)

    val round = state.approvedStashRound(target.path)
    val message = "$APPROVED_DISCARD_MESSAGE_PREFIX${target.path} round=$round"
    // Snapshot the stack top before the push so a terminated push can be told apart from a
    // pre-existing entry (a prior approved stash, an external stash) never mistaken for it.
    val beforeTop = try {
        context.git.stashTopOid(directory)
    } catch (error: GitQueryException) {
        context.log.warn(
            "approved stash: could not read stash top before push (${target.path}); " +
                "ghost detection after a terminated push will be less precise: " +
                error.result.diagnostic(),
        )
        null
    }
    val stashResult = context.git.stashPaths(directory, message, paths.toList())
    if (stashResult.ok) {
        // Locate our stash by its unique message, never by the stack top.
        val oid = try {
            context.git.stashOidByMessage(directory, message)
        } catch (error: GitQueryException) {
            issues += OperationIssue(
                stage,
                OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                target.path,
                severity = OperationIssueSeverity.ERROR,
                diagnostic = error.result.diagnostic(),
            )
            return ApprovedStashOutcome(
                state.withTrackedStash(target.path, StashPurpose.APPROVED_DISCARD, message, oid = null),
                created = false,
            )
        }
        if (oid == null) return revalidateUncreatedStash(context, target, directory, state, issues, stage)
        context.log.info("approved stash: ok (${target.path}, round=$round, oid=$oid)")
        return ApprovedStashOutcome(
            state.withTrackedStash(target.path, StashPurpose.APPROVED_DISCARD, message, oid),
            created = true,
        )
    }

    // The push failed. A terminated push may have written refs/stash before dying; track any
    // entry it created so recovery can apply it instead of leaving a torn stash.
    if (stashResult.failureKind.isTermination) {
        trackGhostStashIfCreated(
            context, target, directory, beforeTop, message, state, StashPurpose.APPROVED_DISCARD,
        )?.let { return ApprovedStashOutcome(it, created = true) }
    }
    issues += OperationIssue(
        stage,
        OperationIssueCode.STASH_FAILED,
        target.path,
        diagnostic = stashResult.diagnostic(),
    )
    return ApprovedStashOutcome(state, created = false)
}

/**
 * Re-validates [paths] after a stash push that reported ok but created no entry, instead of
 * assuming they were discarded: paths that are no longer collisions (gone or tracked) are a
 * harmless no-op; paths that are still approved untracked collisions fail closed.
 */
private fun revalidateUncreatedStash(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    state: SwitchState,
    issues: MutableList<OperationIssue>,
    stage: OperationStage,
): ApprovedStashOutcome {
    val stillColliding = approvedCollisionPaths(context, target, directory)
    if (stillColliding.isEmpty()) {
        context.log.info("approved stash: no entry created, paths no longer collide - ${target.path}")
        return ApprovedStashOutcome(state, created = false)
    }
    val diagnostic = "approved stash isolation created no entry yet ${stillColliding.size} path(s) " +
        "remain approved untracked collisions: ${stillColliding.sorted().joinToString(", ")}"
    context.log.warn("[fail] $diagnostic (${target.path})")
    issues += OperationIssue(
        stage,
        OperationIssueCode.STASH_FAILED,
        target.path,
        diagnostic = diagnostic,
    )
    return ApprovedStashOutcome(state, created = false)
}
