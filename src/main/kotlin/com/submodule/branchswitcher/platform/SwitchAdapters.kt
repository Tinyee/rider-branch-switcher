package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.operation.OperationProgress
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.CancellationHandle
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/** Adapts an IntelliJ [ProgressIndicator] to a pure [CancellationHandle]. */
class ProgressCancellationHandle(
    private val indicator: ProgressIndicator?,
) : CancellationHandle {
    override fun checkCanceled() { indicator?.checkCanceled() }
    override val isCanceled: Boolean get() = indicator?.isCanceled == true
}

/** Adapts an IntelliJ [ProgressIndicator] to pure progress and cancellation handles. */
class ProgressIndicatorHandle(
    private val indicator: ProgressIndicator,
) : OperationProgress {
    override fun checkCanceled() = indicator.checkCanceled()
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
data class VcsRefreshResult(
    val refreshedRepositories: Int,
    val failures: Map<String, String>,
)

@Suppress("TooGenericExceptionCaught") // VFS and repository adapters expose unrelated per-root failures
fun refreshVcsRepos(
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
        } catch (e: Exception) {
            if (platformCancellationClassifier.isCancellation(e)) throw e
            failures[path] = "${e.javaClass.simpleName}: ${e.message}"
            log.logFailure("[vcs] $path refresh failed", e)
        }
    }
    return VcsRefreshResult(refreshedRepositories, failures)
}

fun logVcsRefresh(log: AppLogger, result: VcsRefreshResult) {
    log.debug(
        "[vcs] refreshed ${result.refreshedRepositories} repo(s), failures=${result.failures.size}",
    )
}

/**
 * Runs the shared post-mutation VCS refresh tail: queries the touched repositories in
 * the background, then on the UI thread logs the result and runs [onUi]. Every
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

/**
 * Platform classifier: recognizes JDK CancellationException, IntelliJ ProcessCanceledException,
 * and git queries cancelled mid-run (failureKind CANCELLED/INTERRUPTED), so a superseded probe
 * is treated as cancellation instead of a noisy failure.
 */
val platformCancellationClassifier = CancellationClassifier { e ->
    e is java.util.concurrent.CancellationException ||
        e is ProcessCanceledException ||
        (e is GitQueryException && e.result.failureKind in
            setOf(GitFailureKind.CANCELLED, GitFailureKind.INTERRUPTED))
}
