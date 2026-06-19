package com.submodule.branchswitcher.log

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

/** Creates an [AppLogger] that appends each message via [collect];
 *  warn/error/debug are prefixed, info/activity are bare. */
fun createStringAppender(collect: (String) -> Unit): AppLogger = object : AppLogger {
    override fun info(msg: String) { collect(msg) }
    override fun warn(msg: String) { collect("[warn] $msg") }
    override fun error(msg: String) { collect("[error] $msg") }
    override fun debug(msg: String) { collect("[debug] $msg") }
    override fun activity(msg: String) { collect(msg) }
}
