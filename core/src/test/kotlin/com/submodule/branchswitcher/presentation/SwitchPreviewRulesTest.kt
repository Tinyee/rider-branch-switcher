package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPreviewRulesTest {

    private fun makeRequest(
        dirty: DirtyAction = DirtyAction.Stash,
    ): ResolvedSwitchRequest = ResolvedSwitchRequest.from(
        Preset("test", "main"),
        SwitchOptions(dirty = dirty, pull = true, fetchFirst = true),
    )

    private fun row(
        exists: Boolean = true,
        dirtyCount: Int = 0,
        current: String? = "main",
        hasLocal: Boolean = true,
        hasRemote: Boolean = true,
        probeError: String? = null,
    ) = PreflightRow(
        label = "test",
        path = ".",
        target = "main",
        exists = exists,
        current = current,
        dirtyCount = dirtyCount,
        hasLocal = hasLocal,
        hasRemote = hasRemote,
        probeError = probeError,
    )

    @Test
    fun `force warning follows strategy repository and dirty state`() {
        val request = makeRequest(DirtyAction.Force)
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = 3))))
        assertFalse(shouldShowForceWarning(request, listOf(row(dirtyCount = 0))))
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = -1))))
        assertFalse(shouldShowForceWarning(request, listOf(row(exists = false, dirtyCount = -1))))
        assertFalse(shouldShowForceWarning(makeRequest(DirtyAction.Stash), listOf(row(dirtyCount = 5))))
    }

    // ── summary counts ────────────────────────────────────────────────────────

    @Test
    fun `a probe error counts as neither to-switch nor missing`() {
        // The load-bearing condition. A probe-error row has current == null, so it does not
        // equal the target: needsSwitch is true. Its hasLocal/hasRemote are fail-closed false,
        // so branchMissing is true. Without the probeError == null term the row would be
        // counted in BOTH directions and the summary would state two things the probe never
        // established.
        val counts = switchPreviewCounts(
            listOf(
                row(current = null, hasLocal = false, hasRemote = false, probeError = "could not inspect"),
                row(current = "dev"),  // target is "main", so this one genuinely needs a switch
            ),
        )

        assertEquals("the errored row is not a known switch", 1, counts.toSwitch)
        assertEquals("...and not a known missing branch either", 0, counts.missingBranch)
    }

    @Test
    fun `the counts partition the rows by what is known about each`() {
        val counts = switchPreviewCounts(
            listOf(
                row(current = "dev"),                       // needs switch
                row(current = "main"),                      // already there
                row(current = null, hasLocal = false, hasRemote = false),  // missing branch
                row(current = "main", exists = false),      // missing directory
                row(current = "main", dirtyCount = 2),      // clean target, dirty tree
            ),
        )

        assertEquals(5, counts.total)
        assertEquals(1, counts.toSwitch)
        // The already-there row and the missing-dir row. The missing-branch row is NOT here:
        // it has current == null, which does not equal the target, so needsSwitch is true.
        assertEquals(2, counts.noChange)
        assertEquals(1, counts.missingBranch)
        assertEquals(1, counts.missingDir)
        assertEquals(1, counts.dirty)
    }

    // ── summary parts ─────────────────────────────────────────────────────────

    @Test
    fun `a zero count is omitted rather than reported`() {
        val parts = switchPreviewSummaryParts(
            switchPreviewCounts(listOf(row(current = "dev"))),
        )

        assertEquals(
            "only the two unconditional parts survive a summary with nothing else to say",
            listOf(
                SwitchPreviewSummaryPart.Repos::class,
                SwitchPreviewSummaryPart.ToSwitch::class,
            ),
            parts.map { it::class },
        )
    }

    @Test
    fun `each non-zero count appears once in reading order`() {
        val parts = switchPreviewSummaryParts(
            switchPreviewCounts(
                listOf(
                    row(current = "dev", dirtyCount = 1),
                    row(current = null, hasLocal = false, hasRemote = false),
                    row(current = "main", exists = false),
                ),
            ),
        )

        assertEquals(
            listOf(
                SwitchPreviewSummaryPart.Repos::class,
                SwitchPreviewSummaryPart.ToSwitch::class,
                SwitchPreviewSummaryPart.Dirty::class,
                SwitchPreviewSummaryPart.MissingBranch::class,
                SwitchPreviewSummaryPart.MissingDir::class,
            ),
            parts.map { it::class },
        )
        assertEquals("each part carries its own count", 1, (parts[2] as SwitchPreviewSummaryPart.Dirty).count)
    }

    // ── OK button label ───────────────────────────────────────────────────────

    @Test
    fun `the OK label names the discard only when one needs confirming`() {
        assertEquals("dialog.collision.discard.ok", switchOkButtonKey(needsDiscardConfirm = true))
        assertEquals("dialog.switch.title", switchOkButtonKey(needsDiscardConfirm = false))
    }
}
