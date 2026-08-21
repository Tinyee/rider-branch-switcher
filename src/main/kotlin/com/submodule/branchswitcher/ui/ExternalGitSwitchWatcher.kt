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
 * decisions it makes per poll — [mainReflogPath] and [reflogStampUpdate] — are
 * extracted as internal functions so they are unit-testable without an Alarm.
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
        if (!shouldWatch()) {
            alarm.cancelAllRequests()
            return
        }
        val root = gitRoot()
        val reflog = root?.let(::mainReflogPath)
        if (reflog == null) {
            // `.git` is not resolvable yet (root temporarily unavailable, worktree
            // gitdir file transiently unreadable). Re-queue the watch instead of
            // returning, which would stop it permanently until a hide/show cycle.
            logUnresolved(root)
            restart()
            return
        }
        val stamp = runCatching { reflog.lastModified() }.getOrElse {
            log.warn(
                "reflog lastModified failed: ${it.javaClass.simpleName}: ${it.message}; " +
                    "stamp reset to -1 (may fire a spurious external switch)",
            )
            -1L
        }
        val previous = lastReflogStamp
        lastReflogStamp = reflogStampUpdate(previous, stamp, onExternalChange)
        when {
            previous < 0 -> log.debug("watcher initial stamp captured: $stamp")
            stamp != previous -> log.info("external switch detected: reflog stamp $previous -> $stamp")
            else -> log.debug("watcher noop: stamp=$stamp")
        }
        restart()
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
