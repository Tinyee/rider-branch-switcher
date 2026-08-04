package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.git.SubmoduleRegistrationQuery
import java.io.File

/** One consistent view of the currently checked-out `.gitmodules` graph. */
class SubmoduleTopology internal constructor(
    val paths: Set<String>,
    val byPath: Map<String, SubmoduleRegistration>,
) {
    fun isUnregistered(path: String): Boolean = path != "." && path !in paths
}

/** Loads one complete, structured view of the checked-out submodule graph. */
fun SubmoduleRegistrationQuery.loadSubmoduleTopology(gitRoot: File): SubmoduleTopology {
    val registrations = registeredSubmodules(gitRoot)
    return SubmoduleTopology(
        registrations.mapTo(linkedSetOf(), SubmoduleRegistration::path),
        registrations.associateBy(SubmoduleRegistration::path),
    )
}
