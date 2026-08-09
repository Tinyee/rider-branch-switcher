package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.RepositoryStateGitClient
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.rethrowIfCancellation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

data class RepositoryStateSnapshot(
    val requestId: Long,
    val branches: Map<String, String?>,
    val dirtyRepositories: Map<String, Boolean>,
)

class RepositoryStateRequest internal constructor(
    val id: Long,
    val root: Path,
    val paths: List<String>,
)

/**
 * Reads branch and dirty state without owning coroutine or Swing lifecycle.
 *
 * Requests carry a generation so callers can discard results superseded while
 * Git probes were running.
 */
class RepositoryStateDetector(
    private val log: AppLogger,
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
) {
    private val latestRequestId = AtomicLong(0)

    fun begin(root: Path, paths: Collection<String>): RepositoryStateRequest =
        RepositoryStateRequest(
            id = latestRequestId.incrementAndGet(),
            root = root,
            paths = paths.distinct(),
        )

    @Suppress("TooGenericExceptionCaught")
    fun detect(
        request: RepositoryStateRequest,
        git: RepositoryStateGitClient,
    ): RepositoryStateSnapshot {
        val branches = LinkedHashMap<String, String?>(request.paths.size)
        val dirty = LinkedHashMap<String, Boolean>(request.paths.size)
        for (path in request.paths) {
            if (!isLatest(request)) break
            val dir = if (path == ".") request.root.toFile() else request.root.resolve(path).toFile()
            try {
                val inspection = when {
                    !dir.exists() -> null
                    git is RepositoryStateBatchGitClient -> git.inspectRepositoryState(dir)
                    else -> null
                }
                branches[path] = when {
                    inspection?.isGitRepository == true -> inspection.currentBranch
                    inspection != null -> null
                    dir.exists() -> git.currentBranch(dir)
                    else -> null
                }
                dirty[path] = when {
                    inspection != null -> inspection.isGitRepository && inspection.dirtyFileCount > 0
                    dir.exists() -> git.isDirty(dir)
                    else -> false
                }
            } catch (e: Exception) {
                cancellationClassifier.rethrowIfCancellation(e)
                branches[path] = null
                dirty[path] = false
                log.failure("[detect] $path failed", e)
            }
        }
        return RepositoryStateSnapshot(request.id, branches, dirty)
    }

    fun isLatest(snapshot: RepositoryStateSnapshot): Boolean =
        snapshot.requestId == latestRequestId.get()

    fun invalidate() {
        latestRequestId.incrementAndGet()
    }

    private fun isLatest(request: RepositoryStateRequest): Boolean =
        request.id == latestRequestId.get()
}
