package com.submodule.branchswitcher.log

import com.intellij.openapi.diagnostic.Logger as IdeaLogger

/**
 * Structured logger that routes to both the IntelliJ diagnostic log
 * and the tool window log panel (via [onAppend]).
 *
 * Replaces the previous `(String) -> Unit` lambda with leveled methods,
 * eliminating string-based prefix matching for color coding.
 */
interface AppLogger {
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String)
    fun debug(msg: String)
    /** User-initiated actions / operations (derive, rollback, switch start/end). Rendered in blue. */
    fun activity(msg: String)
}

data class LogEntry(val level: Level, val message: String) {
    enum class Level { INFO, WARN, ERROR, DEBUG, ACTIVITY }
}

/** Creates an [AppLogger] that appends every message as INFO to [collect]. */
fun createStringAppender(collect: (String) -> Unit): AppLogger = object : AppLogger {
    override fun info(msg: String) { collect(msg) }
    override fun warn(msg: String) { collect("[warn] $msg") }
    override fun error(msg: String) { collect("[error] $msg") }
    override fun debug(msg: String) { collect("[debug] $msg") }
    override fun activity(msg: String) { collect(msg) }
}

class ToolWindowLogger(
    private val onAppend: (LogEntry) -> Unit,
) : AppLogger {

    private val ideaLogger = IdeaLogger.getInstance("SubmoduleBranchSwitcher")

    override fun info(msg: String) {
        onAppend(LogEntry(LogEntry.Level.INFO, msg))
    }

    override fun warn(msg: String) {
        ideaLogger.warn(msg)
        onAppend(LogEntry(LogEntry.Level.WARN, msg))
    }

    override fun error(msg: String) {
        // Use warn() instead of error() — most error() call sites report expected
        // business failures (branch not found, dirty workspace, pull failed, etc.)
        // that should show red in the tool window but must NOT trigger Rider's
        // Fatal Errors / error-reporting system.
        ideaLogger.warn(msg)
        onAppend(LogEntry(LogEntry.Level.ERROR, msg))
    }

    override fun debug(msg: String) {
        ideaLogger.debug(msg)
        onAppend(LogEntry(LogEntry.Level.DEBUG, msg))
    }

    override fun activity(msg: String) {
        onAppend(LogEntry(LogEntry.Level.ACTIVITY, msg))
    }
}
