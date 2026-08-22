package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.operation.OperationProgress
import com.submodule.branchswitcher.switch.OperationCancelledException
import com.submodule.branchswitcher.switch.OperationControl
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/** Adapts an IntelliJ [ProgressIndicator] to a pure [OperationControl]. */
class ProgressOperationControl(
    private val indicator: ProgressIndicator?,
) : OperationControl {
    override fun checkCancelled() {
        try {
            indicator?.checkCanceled()
        } catch (e: ProcessCanceledException) {
            throw OperationCancelledException("operation cancelled", e)
        }
    }

    override val isCanceled: Boolean get() = indicator?.isCanceled == true
}

/** Adapts an IntelliJ [ProgressIndicator] to pure progress and cancellation handles. */
class ProgressIndicatorHandle(
    private val indicator: ProgressIndicator,
) : OperationProgress {
    override fun checkCancelled() {
        try {
            indicator.checkCanceled()
        } catch (e: ProcessCanceledException) {
            throw OperationCancelledException("operation cancelled", e)
        }
    }

    override val isCanceled: Boolean get() = indicator.isCanceled
    override var fraction: Double
        get() = indicator.fraction
        set(value) { indicator.fraction = value }
    override var text: String?
        get() = indicator.text
        set(value) { indicator.text = value }
    override var text2: String?
        get() = indicator.text2
        set(value) { indicator.text2 = value }
    override var isIndeterminate: Boolean
        get() = indicator.isIndeterminate
        set(value) { indicator.isIndeterminate = value }
}

/**
 * Refreshes VCS status for main repo + submodule paths.
 * Shared by both tool-window switch and shortcut action switch.
 */
private data class VcsRefreshResult(
    val refreshedRepositories: Int,
    val failures: Map<String, String>,
)

@Suppress("TooGenericExceptionCaught") // VFS and repository adapters expose unrelated per-root failures
private fun refreshVcsRepos(
    project: Project,
    root: Path,
    submodulePaths: Set<String>,
    log: AppLogger,
): VcsRefreshResult {
    val lfs = LocalFileSystem.getInstance()
    val mgr = GitRepositoryManager.getInstance(project)
    var refreshedRepositories = 0
    val failures = linkedMapOf<String, String>()
    for (path in listOf(".") + submodulePaths) {
        val dir = if (path == ".") root.toFile() else root.resolve(path).toFile()
        try {
            val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
            vf.refresh(false, true)
            mgr.getRepositoryForRoot(vf)?.update()
            refreshedRepositories++
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: ProcessCanceledException) {
            // A platform cancel surfaces as OperationCancelledException so the caller
            // treats this refresh as cancelled, not as a per-root VCS failure.
            throw OperationCancelledException("operation cancelled", e)
        } catch (e: Exception) {
            failures[path] = "${e.javaClass.simpleName}: ${e.message}"
            log.logFailure("[vcs] $path refresh failed", e)
        }
    }
    return VcsRefreshResult(refreshedRepositories, failures)
}

private fun logVcsRefresh(log: AppLogger, result: VcsRefreshResult) {
    log.debug(
        "[vcs] refreshed ${result.refreshedRepositories} repo(s), failures=${result.failures.size}",
    )
}

/**
 * Runs the shared post-mutation VCS refresh tail: queries the touched repositories
 * synchronously on the caller's thread (callers run on a background worker), then
 * schedules the log and [onUi] on the UI thread via [uiLater]. Every
 * repository-mutation entry point uses this so the tail cannot drift between them.
 */
fun refreshVcsTail(
    project: Project,
    root: Path,
    paths: Set<String>,
    log: AppLogger,
    uiLater: (() -> Unit) -> Unit,
    onUi: () -> Unit,
) {
    val refreshResult = refreshVcsRepos(project, root, paths, log)
    uiLater {
        logVcsRefresh(log, refreshResult)
        onUi()
    }
}
