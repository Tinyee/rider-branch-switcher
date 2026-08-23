package com.submodule.branchswitcher.ui

import com.intellij.util.Alarm
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Path

/** How often the external-switch watch polls the main reflog. */
private const val REFLOG_WATCH_INTERVAL_MS = 2000L

/**
 * Watches the main repository's reflog while the tool window is visible and calls
 * [onExternalChange] when HEAD moves outside the plugin (a terminal or a second
 * IDE), so the panel can refresh its state.
 *
 * The panel owns the [Alarm] (and its dispose lifecycle); this class only queues
 * and cancels requests on it and keeps the last-seen reflog stamp. The pure
 * decisions it makes per poll — [mainReflogPath], [reflogStampUpdate], and the
 * [pollDecision] control flow — are extracted as internal functions so they are
 * unit-testable without an Alarm.
 */
internal class ExternalGitSwitchWatcher(
    private val alarm: Alarm,
    private val log: AppLogger,
    private val gitRoot: () -> Path?,
    private val shouldWatch: () -> Boolean,
    private val onExternalChange: () -> Unit,
) {
    private var lastReflogStamp: Long = -1L
    /** Last unresolved root already warned about, so a persistent 2s-per-poll failure stays visible but quiet. */
    private var lastUnresolvedWarning: String? = null

    /** Cancels any pending request, then re-queues when [shouldWatch] is still true. */
    fun restart() {
        alarm.cancelAllRequests()
        if (!shouldWatch()) return
        alarm.addRequest({ poll() }, REFLOG_WATCH_INTERVAL_MS)
    }

    /** Cancels the poll without re-queuing (tool window hidden). */
    fun stop() {
        alarm.cancelAllRequests()
    }

    private fun poll() {
        val root = gitRoot()
        when (val action = pollDecision(shouldWatch(), root?.let(::mainReflogPath), File::lastModified, lastReflogStamp)) {
            WatchPollAction.Stop -> alarm.cancelAllRequests()
            WatchPollAction.Requeue -> {
                // `.git` is not resolvable yet (root temporarily unavailable, worktree
                // gitdir file transiently unreadable). Re-queue the watch instead of
                // stopping, which would end it permanently until a hide/show cycle.
                logUnresolved(root)
                restart()
            }
            is WatchPollAction.Observe -> {
                action.readError?.let { error ->
                    log.warn(
                        "reflog lastModified failed: ${error.javaClass.simpleName}: ${error.message}; " +
                            "stamp reset to -1 (may fire a spurious external switch)",
                    )
                }
                lastReflogStamp = reflogStampUpdate(action.previous, action.stamp, onExternalChange)
                when {
                    action.previous < 0 -> log.debug("watcher initial stamp captured: ${action.stamp}")
                    action.stamp != action.previous ->
                        log.info("external switch detected: reflog stamp ${action.previous} -> ${action.stamp}")
                    else -> log.debug("watcher noop: stamp=${action.stamp}")
                }
                restart()
            }
        }
    }

    /** Warns at most once per unresolved root, so a persistent 2s-per-poll failure stays visible but quiet. */
    private fun logUnresolved(root: Path?) {
        // The tool window log is copyable/exportable, so only the directory name (not
        // the full path) reaches the panel.
        val detail = root?.fileName?.toString() ?: "<root unavailable>"
        if (lastUnresolvedWarning == detail) return
        lastUnresolvedWarning = detail
        log.warn("reflog path unresolved (root=$detail); re-queueing watcher")
    }
}

/**
 * Resolves the main repository's `git logs/HEAD` path. For a worktree the `.git`
 * entry is a file whose `gitdir: <path>` line points at the real git directory;
 * any other layout returns null so the caller re-queues instead of stopping.
 */
internal fun mainReflogPath(root: Path): File? {
    val git = root.resolve(".git").toFile()
    val gitDir = if (git.isDirectory) {
        git
    } else if (git.isFile) {
        // A worktree's `.git` is a single `gitdir: <path>` line; anything else is not
        // resolvable, so fall through to null instead of treating the line as a path.
        val gitDirLine = runCatching { git.readLines().firstOrNull() }.getOrNull()
        gitDirLine?.takeIf { it.startsWith("gitdir: ") }?.removePrefix("gitdir: ")?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { root.resolve(it).toFile() }
    } else {
        null
    } ?: return null
    return File(gitDir, "logs/HEAD")
}

/**
 * Returns the next stamp to remember, invoking [onChange] when the reflog moved since
 * [lastStamp]. Extracted from the watcher so the first poll (initializing the stamp)
 * and subsequent moves are unit-testable decisions without an Alarm.
 */
internal fun reflogStampUpdate(lastStamp: Long, newStamp: Long, onChange: () -> Unit): Long {
    if (lastStamp >= 0 && newStamp != lastStamp) onChange()
    return newStamp
}

/** One poll tick's decision, produced by [pollDecision] and executed by the watcher's `poll()`. */
internal sealed interface WatchPollAction {
    /** The panel is hidden/disposed: cancel the watch without re-queuing. */
    data object Stop : WatchPollAction
    /** The reflog is not resolvable yet: re-queue on the next interval. */
    data object Requeue : WatchPollAction
    /**
     * The reflog was readable: remember [stamp] ([reflogStampUpdate] decides whether to
     * fire the external-change callback). [readError] is set when reading the stamp
     * failed, in which case [stamp] is the -1 sentinel.
     */
    data class Observe(val stamp: Long, val previous: Long, val readError: Throwable? = null) : WatchPollAction
}

/**
 * Decides what the watcher does after one poll tick, without touching the [Alarm] so the
 * stop / re-queue / stamp-failure branches are unit-testable. [reflog] is the resolved
 * `logs/HEAD` file (null when the root or git metadata is unavailable); [readStamp] is
 * the `lastModified`-style read whose failure is mapped to the -1 stamp sentinel.
 */
@Suppress("TooGenericExceptionCaught") // any read failure must surface as the -1 sentinel
internal fun pollDecision(
    shouldWatch: Boolean,
    reflog: File?,
    readStamp: (File) -> Long,
    previous: Long,
): WatchPollAction {
    if (!shouldWatch) return WatchPollAction.Stop
    if (reflog == null) return WatchPollAction.Requeue
    return try {
        WatchPollAction.Observe(readStamp(reflog), previous)
    } catch (error: Exception) {
        // A failed lastModified read is a stamp change (the poll refreshes rather than
        // silently staying on the old stamp), so surface it as -1 with the error attached.
        // JVM Errors propagate: an OOM while reading the stamp must not be masked as a stamp change.
        WatchPollAction.Observe(-1L, previous, error)
    }
}
