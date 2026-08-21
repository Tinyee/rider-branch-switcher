package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

class UiRulesTest {
    @Test
    fun `reflog watch only runs while the panel is visible and project is alive`() {
        assertTrue(shouldRunReflogWatch(isShowing = true, projectDisposed = false))
        assertFalse(shouldRunReflogWatch(isShowing = false, projectDisposed = false))
        assertFalse(shouldRunReflogWatch(isShowing = true, projectDisposed = true))
    }


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

    @Test
    fun `clipboard text reader accepts text and rejects non-text contents`() {
        val clipboard = Clipboard("test")
        clipboard.setContents(StringSelection("""{"presets":[]}"""), null)
        assertEquals("""{"presets":[]}""", clipboardTextOrNull(clipboard))

        clipboard.setContents(NonTextTransferable, null)
        assertNull(clipboardTextOrNull(clipboard))
    }

    @Test
    fun `collision discard summary is a pure function of the checkbox state`() {
        val all = Bundle.msg("dialog.collision.discard.summary.all", 3)
        val auto = Bundle.msg("dialog.collision.discard.summary.auto", 3, 2)
        val meta = Bundle.msg("dialog.collision.discard.summary.meta", 2)
        val metaAuto = Bundle.msg("dialog.collision.discard.summary.meta.auto", 2)

        assertEquals(all, collisionDiscardSummary(onlyMeta = false, autoMeta = false, metaCount = 2, total = 3))
        assertEquals(auto, collisionDiscardSummary(onlyMeta = false, autoMeta = true, metaCount = 2, total = 3))
        assertEquals(meta, collisionDiscardSummary(onlyMeta = true, autoMeta = false, metaCount = 2, total = 3))
        // Both on: "auto" stays visible no matter which checkbox was clicked last.
        assertEquals(metaAuto, collisionDiscardSummary(onlyMeta = true, autoMeta = true, metaCount = 2, total = 3))
    }

    @Test
    fun `collision file note covers every discard state`() {
        val auto = Bundle.msg("dialog.collision.discard.meta.auto")
        val safe = Bundle.msg("dialog.collision.discard.meta.safe")
        val kept = Bundle.msg("dialog.collision.discard.kept")
        val deleted = Bundle.msg("dialog.collision.discard.deleted")

        assertEquals(auto, collisionFileNote(isMeta = true, onlyMeta = true, autoMeta = true))
        assertEquals(auto, collisionFileNote(isMeta = true, onlyMeta = false, autoMeta = true))
        assertEquals(safe, collisionFileNote(isMeta = true, onlyMeta = true, autoMeta = false))
        assertEquals(safe, collisionFileNote(isMeta = true, onlyMeta = false, autoMeta = false))
        assertEquals(kept, collisionFileNote(isMeta = false, onlyMeta = true, autoMeta = true))
        assertEquals(kept, collisionFileNote(isMeta = false, onlyMeta = true, autoMeta = false))
        assertEquals(deleted, collisionFileNote(isMeta = false, onlyMeta = false, autoMeta = true))
        assertEquals(deleted, collisionFileNote(isMeta = false, onlyMeta = false, autoMeta = false))
    }

    @Test
    fun `collision confirmation is needed while a non-auto-approved file remains`() {
        assertTrue(collisionDiscardNeedsConfirm(listOf("Assets/A.prefab.meta", "Assets/A.prefab"), autoMeta = false))
        assertTrue(collisionDiscardNeedsConfirm(listOf("Assets/A.prefab.meta", "Assets/A.prefab"), autoMeta = true))
        assertTrue(collisionDiscardNeedsConfirm(listOf("Assets/A.prefab"), autoMeta = true))
        assertFalse(collisionDiscardNeedsConfirm(listOf("Assets/A.prefab.meta", "Assets/B.unity.meta"), autoMeta = true))
        assertTrue(collisionDiscardNeedsConfirm(listOf("Assets/A.prefab.meta"), autoMeta = false))
    }

    @Test
    fun `collision file meta detection matches only the meta suffix`() {
        assertTrue(isCollisionFileMeta("Assets/A.prefab.meta"))
        assertTrue(isCollisionFileMeta("Assets/.meta"))
        assertFalse(isCollisionFileMeta("Assets/A.prefab"))
        assertFalse(isCollisionFileMeta("Assets/meta"))
    }

    private object NonTextTransferable : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = error("not needed")
    }
}
