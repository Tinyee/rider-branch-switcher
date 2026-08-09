package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import java.io.File
import java.nio.file.Paths

/** True only when repository metadata proves that a submodule worktree is unrelated or misplaced. */
fun isUnassociatedSubmoduleWorktree(
    projectRoot: File,
    path: String,
    worktree: File,
    identity: RepositoryIdentity?,
    expectedGitDirectory: String? = null,
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
    } catch (_: Exception) {
        // Fail closed: an unresolvable path is treated as unassociated so the write is blocked.
        true
    }
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
