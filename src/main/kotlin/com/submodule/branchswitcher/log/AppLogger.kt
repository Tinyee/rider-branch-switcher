package com.submodule.branchswitcher.log

import com.intellij.openapi.diagnostic.Logger as IdeaLogger

/**
 * IntelliJ [AppLogger] implementation that logs to both the IDE diagnostic log
 * (via [IdeaLogger]) and the tool window (via [onAppend]).
 */
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
