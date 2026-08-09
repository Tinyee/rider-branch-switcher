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
        ideaLogger.info(msg)
        onAppend(LogEntry(LogEntry.Level.INFO, msg))
    }

    override fun warn(msg: String) {
        ideaLogger.warn(msg)
        onAppend(LogEntry(LogEntry.Level.WARN, msg))
    }

    override fun warn(msg: String, error: Throwable) {
        ideaLogger.warn(msg, error)
        onAppend(LogEntry(LogEntry.Level.WARN, "$msg: ${error.javaClass.simpleName}: ${error.message}"))
    }

    override fun error(msg: String) {
        // Single-arg error() reports expected business failures (branch not found, dirty
        // workspace, pull failed, etc.) that should show red in the tool window but must
        // NOT trigger Rider's Fatal Errors / error-reporting system. Business failures that
        // carry an exception use failure(msg, error) instead.
        ideaLogger.warn(msg)
        onAppend(LogEntry(LogEntry.Level.ERROR, msg))
    }

    override fun error(msg: String, error: Throwable) {
        // Throwable-carrying error() is reserved for genuine internal defects and reaches
        // the IDE's error-reporting path; expected business failures must use failure().
        ideaLogger.error(msg, error)
        onAppend(LogEntry(LogEntry.Level.ERROR, "$msg: ${error.javaClass.simpleName}: ${error.message}"))
    }

    override fun failure(msg: String, error: Throwable) {
        ideaLogger.warn(msg, error)
        onAppend(LogEntry(LogEntry.Level.WARN, "$msg: ${error.javaClass.simpleName}: ${error.message}"))
    }

    override fun debug(msg: String) {
        // Diagnostic support must not depend on the IDE's debug-log configuration.
        ideaLogger.info(msg)
        onAppend(LogEntry(LogEntry.Level.DEBUG, msg))
    }

    override fun activity(msg: String) {
        ideaLogger.info(msg)
        onAppend(LogEntry(LogEntry.Level.ACTIVITY, msg))
    }
}
