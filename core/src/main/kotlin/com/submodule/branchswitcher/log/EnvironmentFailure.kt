package com.submodule.branchswitcher.log

import com.submodule.branchswitcher.EnvironmentFailure
import java.io.IOException

/**
 * Logs a failure at WARN when it is a known environment/business failure ([EnvironmentFailure]
 * marker, or a path/permission error wrapped by path resolution); otherwise at ERROR, which
 * reaches the IDE fatal-error reporter.
 *
 * Production exception boundaries should route through this instead of calling
 * [AppLogger.error] directly, so expected failures never trigger Fatal Errors while
 * programming defects still do.
 */
fun AppLogger.logFailure(message: String, error: Throwable) {
    if (isEnvironmentFailure(error)) failure(message, error) else error(message, error)
}

/**
 * True when [error] or any wrapped cause is a known environment/business failure rather
 * than a programming defect. Recognizes the [EnvironmentFailure] marker and standard
 * I/O / permission exceptions anywhere in the cause chain, guarding against cycles by
 * reference so a self-referencing cause cannot loop forever.
 */
fun isEnvironmentFailure(error: Throwable): Boolean {
    val visited = java.util.IdentityHashMap<Throwable, Boolean>()
    var cause: Throwable? = error
    while (cause != null && !visited.containsKey(cause)) {
        visited.put(cause, true)
        if (cause is EnvironmentFailure || cause is IOException || cause is SecurityException) return true
        cause = cause.cause
    }
    return false
}
