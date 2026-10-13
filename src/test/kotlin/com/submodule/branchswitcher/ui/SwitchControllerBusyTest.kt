package com.submodule.branchswitcher.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import java.nio.file.Path

/**
 * The derive path's busy gate, driven through a real
 * [com.intellij.openapi.project.Project].
 *
 * A mutation sweep (ROADMAP P3-18) found that claiming the busy indicator here could
 * be deleted outright with every other test still green. This reaches the claim
 * directly: [SwitchController.derivePresetBranch] is the only write entry point that
 * opens no dialog before acquiring the lease, so its claim is on a plain call path.
 */
class SwitchControllerBusyTest : BasePlatformTestCase() {

    /**
     * A derive whose lease is refused must not claim the busy state.
     *
     * The claim sits after `if (job == null) return` on purpose — a rejected derive
     * belongs to whoever holds the lease, and claiming on its behalf would light the
     * progress indicator for an operation that never started.
     */
    fun testARejectedDeriveDoesNotClaimBusy() {
        val service = project.service<BranchSwitcherService>()
        val log = createStringAppender {}
        val progressChanges = mutableListOf<Boolean>()

        val heldLease = service.tryAcquireWrite()
        assertNotNull("the test needs the write gate held to force a rejection", heldLease)

        try {
            val controller = SwitchController(
                project = project,
                service = service,
                mainRepository = MainRepository(project, log),
                log = log,
                onStateChanged = {},
            )
            controller.onInProgressChange = { progressChanges.add(it) }

            controller.derivePresetBranch(
                root = Path.of(project.basePath ?: "."),
                preset = Preset(name = "Test", main = "main", submodules = emptyMap()),
                branchName = "feature/test",
            )
        } finally {
            heldLease?.close()
        }

        assertEquals(
            "a rejected derive must not touch the in-progress state, got: $progressChanges",
            emptyList<Boolean>(),
            progressChanges,
        )
    }
}
