package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import java.io.File

/** True only when repository metadata proves that a submodule worktree is unrelated or misplaced. */
fun isUnassociatedSubmoduleWorktree(
    projectRoot: File,
    path: String,
    worktree: File,
    identity: RepositoryIdentity?,
    expectedGitDirectory: String? = null,
): Boolean {
    if (path == "." || identity == null) return false
    val superproject = identity.superprojectRoot?.let(::File)
        ?: return true
    val root = projectRoot.canonicalFile.toPath()
    val parent = superproject.canonicalFile.toPath()
    val child = worktree.canonicalFile.toPath()
    val gitDirectory = File(identity.gitDirectory).canonicalFile.toPath()
    if (expectedGitDirectory != null && gitDirectory != File(expectedGitDirectory).canonicalFile.toPath()) {
        return true
    }
    val metadataIsExternal = !gitDirectory.startsWith(child)
    val parentIsInProject = parent == root || parent.startsWith(root)
    val childIsBelowParent = child != parent && child.startsWith(parent)
    return !(metadataIsExternal && parentIsInProject && childIsBelowParent)
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
    return File(parentGitDirectory, "modules/${registration.sectionName}").canonicalPath
}
