package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.platform.resolveGitRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The main repository every screen command operates on, and the one place its
 * absence is reported.
 *
 * The project is opened on a directory that may sit below the repository root (or
 * outside a repository entirely), so resolution walks upward to the first `.git`.
 * When that finds nothing, [resolveOrReport] owns the whole consequence — the log
 * line and the notification — because entry points that each restated it had begun
 * to drift: one of them resolved the root directly, skipping the worktree note below.
 *
 * @param basePath the IDE project's base directory, or null when the project has none.
 * @param log receives resolution diagnostics.
 * @param reportMissingRoot shows the user-facing failure. Injected so a test can
 *   observe the refusal without the platform's global notification singleton.
 */
internal class MainRepository(
    private val basePath: () -> String?,
    private val log: AppLogger,
    private val reportMissingRoot: () -> Unit,
) {
    /**
     * Process-wide for this instance; the worktree note describes the project's layout,
     * which does not change while it is open, so it is worth saying once and not per call.
     */
    private var worktreeInfoLogged = false

    /**
     * The repository root, or null when [basePath] holds no repository. Reports nothing:
     * for callers that treat an absent root as an ordinary "nothing to do" (a file-status
     * filter, a state probe) rather than a command that failed.
     */
    fun resolve(): Path? {
        val base = basePath()
        val root = base?.let(Paths::get)?.let(::resolveGitRoot)
        if (root == null) {
            // Only the project directory name reaches the copyable log panel, not the
            // user's absolute filesystem layout.
            log.debug("git root not resolved: basePath=${base?.let { File(it).name }}")
            return null
        }
        // A worktree's `.git` is a file pointing at the real git directory, not a
        // directory — legal, and worth naming so a later reader does not chase it.
        if (!Files.isDirectory(root.resolve(".git")) && !worktreeInfoLogged) {
            worktreeInfoLogged = true
            log.debug("[info] detected git worktree — .git is a file, not a directory")
        }
        return root
    }

    /**
     * The repository root, or null after telling the user why there is none. The refusal
     * is complete on return: log line written, notification shown, caller free to stop.
     */
    fun resolveOrReport(): Path? = resolve() ?: run {
        log.error(MISSING_ROOT_LOG)
        reportMissingRoot()
        null
    }

    companion object {
        /** Log text kept as one constant so every entry point reports the same thing. */
        const val MISSING_ROOT_LOG = "git root not found"
    }

    /**
     * Binds the module to [project] and the platform's real notification, so no caller has to
     * spell out the title and message keys. Mirrors `PresetRepository(project)`.
     */
    constructor(project: Project, log: AppLogger) : this(
        basePath = { project.basePath },
        log = log,
        reportMissingRoot = {
            Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
        },
    )
}
