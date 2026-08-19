package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Paths

/** True only when repository metadata proves that a submodule worktree is unrelated or misplaced. */
@Suppress("TooGenericExceptionCaught") // fail closed on any unresolvable path; the cause is logged when a logger is provided
fun isUnassociatedSubmoduleWorktree(
    projectRoot: File,
    path: String,
    worktree: File,
    identity: RepositoryIdentity?,
    expectedGitDirectory: String? = null,
    log: AppLogger? = null,
): Boolean {
    if (path == ".") return false
    identity ?: return true
    val superproject = identity.superprojectRoot?.let(::File)
        ?: return true
    return try {
        val root = Paths.get(projectRoot.resolvedIdentity())
        val parent = Paths.get(superproject.resolvedIdentity())
        val child = Paths.get(worktree.resolvedIdentity())
        val gitDirectory = Paths.get(File(identity.gitDirectory).resolvedIdentity())
        if (expectedGitDirectory != null && gitDirectory != Paths.get(File(expectedGitDirectory).resolvedIdentity())) {
            return true
        }
        val metadataIsExternal = !gitDirectory.startsWith(child)
        val parentIsInProject = parent == root || parent.startsWith(root)
        val childIsBelowParent = child != parent && child.startsWith(parent)
        !(metadataIsExternal && parentIsInProject && childIsBelowParent)
    } catch (error: Exception) {
        // Fail closed: an unresolvable path is treated as unassociated so the write is blocked.
        log?.warn(
            "submodule association check could not resolve paths " +
                "(path=$path, worktree=${worktree.path}, gitDir=${identity.gitDirectory}, " +
                "superproject=${identity.superprojectRoot}); blocking as unassociated",
            error,
        )
        true
    }
}

/** Message body shared by the repository-association gates (main, submodule, leftover). */
internal fun unassociatedReasonText(identity: RepositoryIdentity?, expectedGitDirectory: String?) =
    "repository is not associated with its superproject; " +
        "actualGitDir=${identity?.gitDirectory}, expectedGitDir=$expectedGitDirectory, " +
        "superproject=${identity?.superprojectRoot}"

/**
 * The gate used by the four repository-association sites (checkpoint / derive / tree
 * step / single switch). Returns the diagnostic reason when [isUnassociatedSubmoduleWorktree]
 * blocks [path], or null when the worktree may proceed. Callers keep their own log prefix
 * and their own block/return; this only dedups the gate call and the message body.
 */
fun unassociatedSubmoduleBlockReason(
    projectRoot: File,
    path: String,
    worktree: File,
    identity: RepositoryIdentity?,
    expectedGitDirectory: String? = null,
    log: AppLogger? = null,
): String? {
    if (!isUnassociatedSubmoduleWorktree(projectRoot, path, worktree, identity, expectedGitDirectory, log)) {
        return null
    }
    return unassociatedReasonText(identity, expectedGitDirectory)
}

/**
 * True when a target the current topology does not register still looks like a canonical
 * submodule leftover: its git metadata lives outside the worktree under a `.git/modules/`
 * directory. The preset's main branch registers such paths again during the switch, so the
 * checkpoint may record them; a standalone `.git` inside the worktree is not and stays
 * fail-closed.
 */
fun isCanonicalLeftoverGitDirectory(
    worktree: File,
    identity: RepositoryIdentity?,
): Boolean {
    identity ?: return false
    val gitDirectory = runCatching { File(identity.gitDirectory).canonicalPath }.getOrElse { return false }
    val worktreePath = runCatching { worktree.canonicalPath }.getOrElse { return false }
    if (gitDirectory == worktreePath || gitDirectory.startsWith(worktreePath + File.separator)) return false
    val modulesMarker = File.separator + ".git" + File.separator + "modules" + File.separator
    return gitDirectory.contains(modulesMarker)
}

fun expectedSubmoduleGitDirectory(
    projectRoot: File,
    registration: SubmoduleRegistration?,
    git: GitRepositoryQuery,
): String? {
    registration ?: return null
    val parent = if (registration.parentPath == ".") {
        projectRoot
    } else {
        resolveGitDir(projectRoot.toPath(), registration.parentPath)
    }
    val parentGitDirectory = git.repositoryIdentity(parent)?.gitDirectory ?: return null
    return File(parentGitDirectory, "modules/${registration.sectionName}").pathIdentity()
}
