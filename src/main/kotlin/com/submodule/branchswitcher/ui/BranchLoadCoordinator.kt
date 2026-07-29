package com.submodule.branchswitcher.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limits concurrent branch discovery across every preset editor in one Tool Window.
 *
 * Branch discovery starts Git processes and drains their output, so expanding a
 * large preset must not create one active process per submodule.
 */
internal class BranchLoadCoordinator(
    private val scope: CoroutineScope,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
) {
    private val permits = Semaphore(maxConcurrentLoads.coerceAtLeast(1))

    fun launch(block: suspend () -> Unit): Job =
        scope.launch {
            permits.withPermit {
                block()
            }
        }

    companion object {
        private const val DEFAULT_MAX_CONCURRENT_LOADS = 4
    }
}
