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

    /** Invoked once per successful resolution, already on the EDT ([scheduleEdt] applied). */
    var onResolved: ((Map<String, String?>) -> Unit)? = null

    /** Resolved path→URL map; empty until the first successful [resolve]. */
    val urls: Map<String, String?>
        get() = resolvedUrls

    private var resolvedUrls: Map<String, String?> = emptyMap()
    private var resolved = false
    private var resolving = false

    /**
     * Starts resolving the `.gitmodules` path→URL map once. A no-op once [urls] has been
     * resolved or while a resolution is already in flight. Runs the query through
     * [branchLoads] (background, bounded, cancelled when the Tool Window closes) and delivers
     * the outcome on the EDT; a failure is logged and leaves the registry retryable.
     */
    fun resolve(root: Path) {
        if (resolved || resolving) return
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
