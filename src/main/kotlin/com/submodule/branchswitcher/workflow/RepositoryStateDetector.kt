package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.RepositoryStateGitClient
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
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
 * Git probes were running. [detect] probes repositories in sequence; callers that
 * want concurrency probe per path via [probe] and assemble with [assembleSnapshot].
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

    /** Probes every requested path in order, stopping as soon as [request] is superseded. */
    fun detect(
        request: RepositoryStateRequest,
        git: RepositoryStateGitClient,
    ): RepositoryStateSnapshot {
        val batch = git as? RepositoryStateBatchGitClient
        val probes = ArrayList<PathProbe?>(request.paths.size)
        for (path in request.paths) {
            if (!isLatest(request)) break
            probes += probePath(request, path, git, batch)
        }
        return assembleSnapshot(request, probes)
    }

    /** Probes a single path; returns null when [request] was superseded before the probe started. */
    internal fun probe(
        request: RepositoryStateRequest,
        path: String,
        git: RepositoryStateGitClient,
    ): PathProbe? = probePath(request, path, git, git as? RepositoryStateBatchGitClient)

    /** Builds a snapshot from per-path probes, in probe order. */
    internal fun assembleSnapshot(
        request: RepositoryStateRequest,
        probes: List<PathProbe?>,
    ): RepositoryStateSnapshot {
        val branches = LinkedHashMap<String, String?>(request.paths.size)
        val dirty = LinkedHashMap<String, Boolean>(request.paths.size)
        probes.forEach { probe ->
            if (probe != null) {
                branches[probe.path] = probe.branch
                dirty[probe.path] = probe.dirty
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

    /** One path's branch + dirty outcome. */
    internal data class PathProbe(
        val path: String,
        val branch: String?,
        val dirty: Boolean,
    )

    /** Probes one path; returns null when [request] was superseded before the probe started. */
    @Suppress("TooGenericExceptionCaught")
    private fun probePath(
        request: RepositoryStateRequest,
        path: String,
        git: RepositoryStateGitClient,
        batch: RepositoryStateBatchGitClient?,
    ): PathProbe? {
        if (!isLatest(request)) return null
        val dir = if (path == ".") request.root.toFile() else request.root.resolve(path).toFile()
        return try {
            val inspection = when {
                !dir.exists() -> null
                batch != null -> batch.inspectRepositoryState(dir)
                else -> null
            }
            val branch = when {
                inspection?.isGitRepository == true -> inspection.currentBranch
                inspection != null -> null
                dir.exists() -> git.currentBranch(dir)
                else -> null
            }
            val dirty = when {
                inspection != null -> inspection.isGitRepository && inspection.dirtyFileCount > 0
                dir.exists() -> git.isDirty(dir)
                else -> false
            }
            PathProbe(path, branch, dirty)
        } catch (e: Exception) {
            cancellationClassifier.rethrowIfCancellation(e)
            log.logFailure("[detect] $path failed", e)
            PathProbe(path, null, false)
        }
    }
}
