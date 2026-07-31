package com.submodule.branchswitcher.git

/** Parses `git status --porcelain=v2 --branch` without depending on localized output. */
internal fun parsePorcelainV2Status(output: String): GitRepositoryInspection {
    var currentBranch: String? = null
    var head: String? = null
    var dirtyFileCount = 0
    output.lineSequence().forEach { line ->
        when {
            line.startsWith(BRANCH_HEAD_PREFIX) -> {
                currentBranch = line.removePrefix(BRANCH_HEAD_PREFIX)
                    .takeUnless { it == DETACHED_HEAD }
            }
            line.startsWith(BRANCH_OID_PREFIX) -> {
                head = line.removePrefix(BRANCH_OID_PREFIX)
                    .takeUnless { it == INITIAL_BRANCH }
            }
            line.isNotBlank() && !line.startsWith("# ") -> dirtyFileCount++
        }
    }
    return GitRepositoryInspection(
        isGitRepository = true,
        currentBranch = currentBranch,
        head = head,
        dirtyFileCount = dirtyFileCount,
    )
}

private const val BRANCH_HEAD_PREFIX = "# branch.head "
private const val BRANCH_OID_PREFIX = "# branch.oid "
private const val DETACHED_HEAD = "(detached)"
private const val INITIAL_BRANCH = "(initial)"
