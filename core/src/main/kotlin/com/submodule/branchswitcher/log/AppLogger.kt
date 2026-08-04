package com.submodule.branchswitcher.log

import java.util.UUID
import java.time.Instant

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
    fun warn(msg: String, error: Throwable) {
        warn("$msg: ${error.javaClass.simpleName}: ${error.message}")
    }
    fun error(msg: String)
    fun error(msg: String, error: Throwable) {
        error("$msg: ${error.javaClass.simpleName}: ${error.message}")
    }
    fun debug(msg: String)
    /** User-initiated actions / operations (derive, rollback, switch start/end). Rendered in blue. */
    fun activity(msg: String)
}

/** Short correlation ID used to group one write workflow in persistent IDE logs. */
fun newOperationId(kind: String): String = "$kind-${UUID.randomUUID().toString().take(8)}"

/** Correlates every phase of one user workflow under a single stable identifier. */
data class OperationContext(
    val id: String,
    val phase: String? = null,
) {
    fun inPhase(name: String): OperationContext = copy(phase = name)

    internal val logLabel: String get() = phase?.let { "$id/$it" } ?: id
}

fun newOperationContext(kind: String): OperationContext = OperationContext(newOperationId(kind))

fun AppLogger.withContext(context: OperationContext): AppLogger = withContext(context.logLabel)

/** Prefixes every message while preserving Throwable-aware logging for diagnostic stack traces. */
fun AppLogger.withContext(context: String): AppLogger {
    val delegate = this
    fun prefix(message: String) = "[$context] $message"
    return object : AppLogger {
        override fun info(msg: String) = delegate.info(prefix(msg))
        override fun warn(msg: String) = delegate.warn(prefix(msg))
        override fun warn(msg: String, error: Throwable) = delegate.warn(prefix(msg), error)
        override fun error(msg: String) = delegate.error(prefix(msg))
        override fun error(msg: String, error: Throwable) = delegate.error(prefix(msg), error)
        override fun debug(msg: String) = delegate.debug(prefix(msg))
        override fun activity(msg: String) = delegate.activity(prefix(msg))
    }
}

data class LogEntry(
    val level: Level,
    val message: String,
    val createdAt: Instant = Instant.now(),
) {
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
