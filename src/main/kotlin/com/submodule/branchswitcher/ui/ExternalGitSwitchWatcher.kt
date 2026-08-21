package com.submodule.branchswitcher.ui

import com.intellij.util.Alarm
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
 * and cancels requests on it and keeps the last-seen reflog stamp.
 */
internal class ExternalGitSwitchWatcher(
    private val alarm: Alarm,
    private val gitRoot: () -> Path?,
    private val shouldWatch: () -> Boolean,
    private val onExternalChange: () -> Unit,
) {
    private var lastReflogStamp: Long = -1L

    /** Cancels any pending request, then re-queues when [shouldWatch] is still true. */
    fun restart() {
        alarm.cancelAllRequests()
        if (!shouldWatch()) return
        alarm.addRequest({ check() }, REFLOG_WATCH_INTERVAL_MS)
    }

    /** Cancels the poll without re-queuing (tool window hidden). */
    fun stop() {
        alarm.cancelAllRequests()
    }

    private fun check() {
        if (!shouldWatch()) {
            alarm.cancelAllRequests()
            return
        }
        val reflog = mainReflogPath()
        if (reflog == null) {
            // `.git` is not resolvable yet (root temporarily unavailable, worktree
            // gitdir file transiently unreadable). Re-queue the watch instead of
            // returning, which would stop it permanently until a hide/show cycle.
            restart()
            return
        }
        val stamp = runCatching { reflog.lastModified() }.getOrElse { -1L }
        if (lastReflogStamp >= 0 && stamp != lastReflogStamp) {
            onExternalChange()
        }
        lastReflogStamp = stamp
        restart()
    }

    private fun mainReflogPath(): File? {
        val root = gitRoot() ?: return null
        val git = root.resolve(".git").toFile()
        val gitDir = if (git.isDirectory) {
            git
        } else if (git.isFile) {
            // Worktree: .git is a file pointing at the real git directory.
            runCatching { git.readLines().firstOrNull()?.removePrefix("gitdir: ")?.trim() }
                .getOrNull()?.let { root.resolve(it).toFile() }
        } else {
            null
        } ?: return null
        return File(gitDir, "logs/HEAD")
    }
}
