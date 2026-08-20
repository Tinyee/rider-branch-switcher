package com.submodule.branchswitcher.log

/**
 * Marker for known environment/business failures (Git query failures, unresolvable
 * submodule topology, path and permission errors). Such failures are reported at
 * WARN and never routed to the IDE fatal-error reporter.
 *
 * Lives beside the logging classifier that recognizes it ([LogFailureClassifier]),
 * so both the classifier and the Git exceptions implement it without a package cycle
 * (the marker has no dependencies).
 */
interface EnvironmentFailure
