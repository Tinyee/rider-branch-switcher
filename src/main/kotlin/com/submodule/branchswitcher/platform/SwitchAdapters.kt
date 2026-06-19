package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.CancellationHandle
import com.submodule.branchswitcher.switch.ProgressHandle

/** Adapts an IntelliJ [ProgressIndicator] to a pure [CancellationHandle]. */
class ProgressCancellationHandle(
    private val indicator: ProgressIndicator?,
) : CancellationHandle {
    override fun checkCanceled() { indicator?.checkCanceled() }
    override val isCanceled: Boolean get() = indicator?.isCanceled == true
}

/** Adapts an IntelliJ [ProgressIndicator] to a pure [ProgressHandle]. */
class ProgressIndicatorHandle(
    private val indicator: ProgressIndicator,
) : ProgressHandle {
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
fun refreshVcsRepos(project: com.intellij.openapi.project.Project, root: java.nio.file.Path, submodulePaths: Set<String>) {
    val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
    val mgr = git4idea.repo.GitRepositoryManager.getInstance(project)
    for (path in listOf(".") + submodulePaths) {
        val dir = if (path == ".") root.toFile() else root.resolve(path).toFile()
        try {
            val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
            vf.refresh(false, true)
            mgr.getRepositoryForRoot(vf)?.update()
        } catch (_: Exception) { /* skip unreachable repos */ }
    }
}

/** Platform classifier: recognizes both JDK CancellationException and IntelliJ ProcessCanceledException. */
val platformCancellationClassifier = CancellationClassifier { e ->
    e is java.util.concurrent.CancellationException ||
        e is com.intellij.openapi.progress.ProcessCanceledException
}
