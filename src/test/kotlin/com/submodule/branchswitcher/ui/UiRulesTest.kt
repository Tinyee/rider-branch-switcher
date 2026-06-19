package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JLabel

class UiRulesTest {

    @Test
    fun `repo status presentation covers initialized matched mismatched and dirty states`() {
        val missing = repoStatusPresentation("SubA", null, "dev", dirty = false)
        assertEquals(RepoStatusTone.NOT_INITIALIZED, missing.tone)
        assertTrue(missing.tooltip.contains("SubA"))

        val matched = repoStatusPresentation("SubA", "dev", "dev", dirty = false)
        assertEquals(RepoStatusTone.MATCHED, matched.tone)
        assertTrue(matched.tooltip.contains("dev"))

        val mismatched = repoStatusPresentation("SubA", "main", "dev", dirty = true)
        assertEquals(RepoStatusTone.MISMATCHED, mismatched.tone)
        assertTrue(mismatched.tooltip.contains("main"))
        assertTrue(mismatched.tooltip.contains("dev"))
        assertTrue(mismatched.tooltip.contains(Bundle.msg("status.tooltip.dirty")))
    }

    @Test
    fun `main status text prioritizes mismatch then dirty and hides matched clean state`() {
        val mismatch = mainStatusText("main", "dev", dirty = true)
        assertTrue(mismatch!!.contains("main"))
        assertTrue(mismatch.contains("dev"))

        val dirty = mainStatusText("dev", "dev", dirty = true)
        assertTrue(dirty!!.contains(Bundle.msg("status.tooltip.dirty")))

        assertNull(mainStatusText("dev", "dev", dirty = false))
    }

    @Test
    fun `strategy summary includes only enabled optional actions`() {
        val basic = strategySummary(DirtyAction.Stash, fetch = false, pull = false, timeoutSeconds = 30)
        assertTrue(basic.contains(Bundle.msg("label.strategy.stash")))
        assertTrue(basic.contains("30s"))
        assertFalse(basic.contains(Bundle.msg("label.strategy.fetch")))
        assertFalse(basic.contains(Bundle.msg("label.strategy.pull")))

        val full = strategySummary(DirtyAction.Force, fetch = true, pull = true, timeoutSeconds = 300)
        assertTrue(full.contains(Bundle.msg("label.strategy.force")))
        assertTrue(full.contains(Bundle.msg("label.strategy.fetch")))
        assertTrue(full.contains(Bundle.msg("label.strategy.pull")))
        assertTrue(full.contains("300s"))
    }




}
