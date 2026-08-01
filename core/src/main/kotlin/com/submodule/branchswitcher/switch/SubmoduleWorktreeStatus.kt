package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.RepositoryIdentity
import java.io.File

/** Whether a repository worktree is associated with a superproject inside this project. */
enum class SubmoduleWorktreeStatus {
    ASSOCIATED,
    NOT_ASSOCIATED,
    UNKNOWN,
}

fun submoduleWorktreeStatus(
    projectRoot: File,
    path: String,
    worktree: File,
    identity: RepositoryIdentity?,
): SubmoduleWorktreeStatus {
    if (path == ".") return SubmoduleWorktreeStatus.ASSOCIATED
    if (identity == null) return SubmoduleWorktreeStatus.UNKNOWN
    val superproject = identity.superprojectRoot?.let(::File)
        ?: return SubmoduleWorktreeStatus.NOT_ASSOCIATED
    val root = projectRoot.canonicalFile.toPath()
    val parent = superproject.canonicalFile.toPath()
    val child = worktree.canonicalFile.toPath()
    val gitDirectory = File(identity.gitDirectory).canonicalFile.toPath()
    val metadataIsExternal = !gitDirectory.startsWith(child)
    val parentIsInProject = parent == root || parent.startsWith(root)
    val childIsBelowParent = child != parent && child.startsWith(parent)
    return if (metadataIsExternal && parentIsInProject && childIsBelowParent) {
        SubmoduleWorktreeStatus.ASSOCIATED
    } else {
        SubmoduleWorktreeStatus.NOT_ASSOCIATED
    }
}
