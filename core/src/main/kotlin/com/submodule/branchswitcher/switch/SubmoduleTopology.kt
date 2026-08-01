package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.git.SubmoduleRegistrationQuery
import java.io.File

/** One consistent view of the currently checked-out `.gitmodules` graph. */
class SubmoduleTopology internal constructor(
    val paths: Set<String>?,
    val byPath: Map<String, SubmoduleRegistration>,
) {
    /** True only when production Git data proves that a submodule path is obsolete. */
    fun isUnregistered(path: String): Boolean = path != "." && paths != null && path !in paths
}

/** Loads structured registrations when available, with a path-only compatibility fallback. */
fun SubmoduleRegistrationQuery.loadSubmoduleTopology(gitRoot: File): SubmoduleTopology {
    val registrations = registeredSubmodules(gitRoot)
    return if (registrations == null) {
        SubmoduleTopology(registeredSubmodulePaths(gitRoot), emptyMap())
    } else {
        SubmoduleTopology(
            registrations.mapTo(linkedSetOf(), SubmoduleRegistration::path),
            registrations.associateBy(SubmoduleRegistration::path),
        )
    }
}
