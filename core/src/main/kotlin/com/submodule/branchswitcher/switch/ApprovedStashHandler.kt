package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Prefix of every approved-discard stash message; used to locate them by message. */
internal const val APPROVED_DISCARD_MESSAGE_PREFIX = "branch-switcher: approved-discard "

/**
 * Result of one approved-collision isolation attempt. Modeled as a sealed outcome so the
 * fail-closed branch is reachable: a caller can only proceed when isolation created a stash
 * or proved there was nothing to isolate; [Blocked] carries the issue and disables the repo.
 */
internal sealed interface ApprovedStashOutcome {
    /** The state to continue the switch with. */
    val state: SwitchState

    /** Isolation succeeded or had nothing to isolate; the switch proceeds. */
    data class Proceed(override val state: SwitchState) : ApprovedStashOutcome

    /** Isolation was required but could not be performed; fail closed with [issue]. */
    data class Blocked(override val state: SwitchState, val issue: OperationIssue) : ApprovedStashOutcome
}

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
 * APPROVED_DISCARD stash in state. The message carries only the operation id and round —
 * never the repository or file paths (they must not surface in the user-visible stash
 * reflog, and they must not make a retained stash from an earlier operation look like this
 * one's).
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
    stage: OperationStage,
): ApprovedStashOutcome {
    val paths = approvedCollisionPaths(context, target, directory)
    if (paths.isEmpty()) return ApprovedStashOutcome.Proceed(state)

    val round = state.approvedStashRound(target.path)
    val message = "$APPROVED_DISCARD_MESSAGE_PREFIX${context.operationId} round=$round"
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
        // Locate our stash by its unique per-operation message, never by the stack top.
        val oid = try {
            context.git.stashOidByMessage(directory, message)
        } catch (error: GitQueryException) {
            return ApprovedStashOutcome.Blocked(
                state.withTrackedStash(
                    target.path, StashPurpose.APPROVED_DISCARD, message, oid = null, approvedPaths = paths,
                ),
                OperationIssue(
                    stage,
                    OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                    target.path,
                    severity = OperationIssueSeverity.ERROR,
                    diagnostic = error.result.diagnostic(),
                ),
            )
        }
        if (oid == null) return revalidateUncreatedStash(context, target, directory, state, stage)
        context.log.info("approved stash: ok (${target.path}, round=$round, oid=$oid)")
        return ApprovedStashOutcome.Proceed(
            state.withTrackedStash(
                target.path, StashPurpose.APPROVED_DISCARD, message, oid, approvedPaths = paths,
            ),
        )
    }

    // The push failed. A terminated push may have written refs/stash before dying; track any
    // entry it created so recovery can apply it instead of leaving a torn stash.
    if (stashResult.failureKind.isTermination) {
        trackGhostStashIfCreated(
            context, target, directory, beforeTop, message, state, StashPurpose.APPROVED_DISCARD,
            approvedPaths = paths,
        )?.let { return ApprovedStashOutcome.Proceed(it) }
    }
    return ApprovedStashOutcome.Blocked(
        state,
        OperationIssue(
            stage,
            OperationIssueCode.STASH_FAILED,
            target.path,
            diagnostic = stashResult.diagnostic(),
        ),
    )
}

/**
 * Re-validates the paths after a stash push that reported ok but created no entry, instead
 * of assuming they were discarded: paths that are no longer collisions (gone or tracked) are
 * a harmless no-op; paths that are still approved untracked collisions fail closed.
 */
private fun revalidateUncreatedStash(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    state: SwitchState,
    stage: OperationStage,
): ApprovedStashOutcome {
    val stillColliding = approvedCollisionPaths(context, target, directory)
    if (stillColliding.isEmpty()) {
        context.log.info("approved stash: no entry created, paths no longer collide - ${target.path}")
        return ApprovedStashOutcome.Proceed(state)
    }
    val diagnostic = "approved stash isolation created no entry yet ${stillColliding.size} path(s) " +
        "remain approved untracked collisions: ${stillColliding.sorted().joinToString(", ")}"
    context.log.warn("[fail] $diagnostic (${target.path})")
    return ApprovedStashOutcome.Blocked(
        state,
        OperationIssue(
            stage,
            OperationIssueCode.STASH_FAILED,
            target.path,
            diagnostic = diagnostic,
        ),
    )
}
