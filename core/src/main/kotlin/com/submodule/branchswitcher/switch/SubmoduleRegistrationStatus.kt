package com.submodule.branchswitcher.switch

/** Registration state of a preset target in the currently checked-out submodule graph. */
enum class SubmoduleRegistrationStatus {
    MAIN,
    REGISTERED,
    UNREGISTERED,
    UNKNOWN,
}

/**
 * Applies one registration policy to every write workflow.
 *
 * UNKNOWN preserves compatibility with test doubles and alternate Git clients
 * that cannot inspect `.gitmodules`; the production Git client always returns
 * a concrete set.
 */
fun submoduleRegistrationStatus(
    path: String,
    registeredPaths: Set<String>?,
): SubmoduleRegistrationStatus = when {
    path == "." -> SubmoduleRegistrationStatus.MAIN
    registeredPaths == null -> SubmoduleRegistrationStatus.UNKNOWN
    path in registeredPaths -> SubmoduleRegistrationStatus.REGISTERED
    else -> SubmoduleRegistrationStatus.UNREGISTERED
}
