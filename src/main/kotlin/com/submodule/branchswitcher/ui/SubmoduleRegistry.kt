package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import java.nio.file.Path

/**
 * Session-scoped owner of the submodule path→URL source shared by every preset editor.
 *
 * Holds the resolved `.gitmodules` map, the single-flight state machine that loads it once
 * off the EDT, and the session's [RemoteBranchCache]. Editors receive the map through
 * [onResolved] (delivered on the EDT via [scheduleEdt]) and read [urls] for a cold start, so
 * `PresetListManager` no longer hand-rolls resolution state. A transient resolution failure
 * stays retryable (the next [resolve] tries again); success is remembered for the Tool
 * Window's lifetime.
 */
internal class SubmoduleRegistry(
    private val branchLoads: BranchLoadCoordinator,
    private val log: AppLogger,
    private val scheduleEdt: (() -> Unit) -> Unit,
) {
    /** Session-scoped cache of `git ls-remote --heads` results shared by every editor. */
    val cache = RemoteBranchCache()

    /**
     * Invoked on the EDT with the current path→URL map whenever the registry has a state
     * worth delivering: after a successful resolve, and on every [resolve] call while already
     * resolved (so an editor created after the first resolution still converges). A failed
     * resolve leaves the previous state (empty or stale) in place and does not fire, so a
     * transient failure is retryable rather than broadcasting a downgrade.
     */
    var onResolved: ((Map<String, String?>) -> Unit)? = null

    /** Resolved path→URL map; empty until the first successful [resolve]. */
    val urls: Map<String, String?>
        get() = resolvedUrls

    @Volatile
    private var resolvedUrls: Map<String, String?> = emptyMap()
    @Volatile
    private var resolved = false
    @Volatile
    private var resolving = false

    /**
     * Starts resolving the `.gitmodules` path→URL map once. A no-op while a resolution is
     * already in flight; once resolved, a later [resolve] (e.g. for a newly created editor)
     * re-delivers the current map on the EDT so late-joining editors converge, without
     * re-querying. Runs the query through [branchLoads] (background, bounded, cancelled when
     * the Tool Window closes) and delivers the outcome on the EDT; a failure is logged and
     * leaves the registry retryable.
     */
    fun resolve(root: Path) {
        if (resolved) {
            // Already have a map: deliver it to whoever is listening now (a fresh editor's
            // cold-start read may have raced the original broadcast). The editor list is
            // rebuilt on reload while this registry is session-scoped, so every new editor
            // must see the state it would have received at creation time.
            scheduleEdt { onResolved?.invoke(resolvedUrls) }
            return
        }
        if (resolving) return
        resolving = true
        branchLoads.discover(
            { client -> client.registeredSubmodules(root.toFile()) },
        ) { result ->
            scheduleEdt {
                resolving = false
                val registrations = result.getOrNull()
                if (registrations == null) {
                    // Leave resolved=false so a later resolve retries.
                    result.exceptionOrNull()?.let { log.logFailure("cannot discover registered submodules", it) }
                } else {
                    resolved = true
                    resolvedUrls = registrations.associate { it.path to it.url }
                    onResolved?.invoke(resolvedUrls)
                }
            }
        }
    }
}
