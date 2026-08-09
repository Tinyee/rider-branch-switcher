package com.submodule.branchswitcher

/**
 * Marker for known environment/business failures (Git query failures, unresolvable
 * submodule topology, path and permission errors). Such failures are reported at
 * WARN and never routed to the IDE fatal-error reporter.
 *
 * Lives in a neutral package so the logging classifier and the Git exceptions can
 * both depend on it without creating a package cycle.
 */
interface EnvironmentFailure
