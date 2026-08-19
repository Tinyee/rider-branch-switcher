package com.submodule.branchswitcher.git

/** Parses `git status --porcelain=v2 --branch` without depending on localized output. */
internal fun parsePorcelainV2Status(output: String): GitRepositoryInspection {
    var currentBranch: String? = null
    var head: String? = null
    // Collect the dirty entries in the same pass so the submodule-only classification
    // below does not rescan the whole output.
    val dirtyEntries = mutableListOf<String>()
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
            line.isNotBlank() && !line.startsWith("# ") -> dirtyEntries += line
        }
    }
    return GitRepositoryInspection(
        isGitRepository = true,
        currentBranch = currentBranch,
        head = head,
        dirtyFileCount = dirtyEntries.size,
        submoduleOnlyDirty = isSubmoduleOnlyDirtyEntries(dirtyEntries),
    )
}

private const val BRANCH_HEAD_PREFIX = "# branch.head "
private const val BRANCH_OID_PREFIX = "# branch.oid "
private const val DETACHED_HEAD = "(detached)"
private const val INITIAL_BRANCH = "(initial)"

/**
 * True when [output] (porcelain v2 status) contains at least one dirty entry and
 * every entry is a submodule change. `git stash` ignores submodules, so a repo
 * classified this way has nothing a superproject stash can protect.
 */
internal fun isSubmoduleOnlyPorcelainStatus(output: String): Boolean =
    isSubmoduleOnlyDirtyEntries(
        output.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("# ") }
            .toList(),
    )

private fun isSubmoduleOnlyDirtyEntries(entries: List<String>): Boolean =
    entries.isNotEmpty() && entries.all { it.isPorcelainSubmoduleChange() }

private fun String.isPorcelainSubmoduleChange(): Boolean {
    if (isEmpty()) return false
    // Entry types: '1' tracked, '2' renamed/copied, 'u' unmerged, '?' untracked.
    // Untracked and unmerged entries are protectable by `git stash` and never
    // count as submodule-only. For tracked entries the XY field must show no
    // superproject index change: `1 M. S...` is a staged gitlink update and
    // must be stashed, while `1 .M S.M.` is dirt inside the submodule worktree.
    // The third field is the submodule status, whose first character is 'N'
    // for non-submodules.
    val type = this[0]
    if (type != '1' && type != '2') return false
    val fields = split(' ', limit = 3)
    if (fields.size < 3) return false
    val xy = fields[1]
    val submoduleStatus = fields[2].firstOrNull() ?: return false
    return xy.firstOrNull() == '.' && submoduleStatus != 'N' && submoduleStatus != '.'
}
