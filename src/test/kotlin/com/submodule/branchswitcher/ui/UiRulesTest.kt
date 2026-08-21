package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
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
    fun `collision decision summary is a pure function of the checkbox state`() {
        val all = Bundle.msg("dialog.collision.discard.summary.all", 3)
        val auto = Bundle.msg("dialog.collision.discard.summary.auto", 3, 2)
        val meta = Bundle.msg("dialog.collision.discard.summary.meta", 2)
        val metaAuto = Bundle.msg("dialog.collision.discard.summary.meta.auto", 2)
        val collisions = listOf("Assets/A.prefab.meta", "Assets/A.prefab", "Assets/B.unity.meta")

        assertEquals(all, collisionDecision(collisions, onlyMeta = false, autoMeta = false).summary)
        assertEquals(auto, collisionDecision(collisions, onlyMeta = false, autoMeta = true).summary)
        assertEquals(meta, collisionDecision(collisions, onlyMeta = true, autoMeta = false).summary)
        // Both on: "auto" stays visible no matter which checkbox was clicked last.
        assertEquals(metaAuto, collisionDecision(collisions, onlyMeta = true, autoMeta = true).summary)
    }

    @Test
    fun `collision decision note covers every discard state`() {
        val auto = Bundle.msg("dialog.collision.discard.meta.auto")
        val safe = Bundle.msg("dialog.collision.discard.meta.safe")
        val kept = Bundle.msg("dialog.collision.discard.kept")
        val deleted = Bundle.msg("dialog.collision.discard.deleted")
        val meta = "Assets/A.prefab.meta"
        val other = "Assets/A.prefab"
        val collisions = listOf(meta, other)

        fun note(isMeta: Boolean, onlyMeta: Boolean, autoMeta: Boolean) =
            collisionDecision(collisions, onlyMeta, autoMeta).noteFor(if (isMeta) meta else other)

        assertEquals(auto, note(isMeta = true, onlyMeta = true, autoMeta = true))
        assertEquals(auto, note(isMeta = true, onlyMeta = false, autoMeta = true))
        assertEquals(safe, note(isMeta = true, onlyMeta = true, autoMeta = false))
        assertEquals(safe, note(isMeta = true, onlyMeta = false, autoMeta = false))
        assertEquals(kept, note(isMeta = false, onlyMeta = true, autoMeta = true))
        assertEquals(kept, note(isMeta = false, onlyMeta = true, autoMeta = false))
        assertEquals(deleted, note(isMeta = false, onlyMeta = false, autoMeta = true))
        assertEquals(deleted, note(isMeta = false, onlyMeta = false, autoMeta = false))
    }

    @Test
    fun `collision confirmation is needed while a non-auto-approved file remains`() {
        val mixed = listOf("Assets/A.prefab.meta", "Assets/A.prefab")
        assertTrue(collisionDecision(mixed, onlyMeta = false, autoMeta = false).needsConfirm)
        assertTrue(collisionDecision(mixed, onlyMeta = false, autoMeta = true).needsConfirm)
        assertTrue(collisionDecision(listOf("Assets/A.prefab"), onlyMeta = false, autoMeta = true).needsConfirm)
        assertFalse(collisionDecision(
            listOf("Assets/A.prefab.meta", "Assets/B.unity.meta"), onlyMeta = false, autoMeta = true,
        ).needsConfirm)
        assertTrue(collisionDecision(listOf("Assets/A.prefab.meta"), onlyMeta = false, autoMeta = false).needsConfirm)
    }

    @Test
    fun `collision file meta detection matches only the meta suffix`() {
        assertTrue(isCollisionFileMeta("Assets/A.prefab.meta"))
        assertTrue(isCollisionFileMeta("Assets/.meta"))
        assertFalse(isCollisionFileMeta("Assets/A.prefab"))
        assertFalse(isCollisionFileMeta("Assets/meta"))
    }

    @Test
    fun `merge branch choices dedups and keeps the current branch selectable`() {
        assertEquals(
            listOf("main", "dev", "feature"),
            mergeBranchChoices("dev", listOf("main", "dev", "feature", "dev")),
        )
        assertEquals(listOf("dev", "main", "feature"), mergeBranchChoices("dev", listOf("main", "feature")))
        assertEquals(listOf("main"), mergeBranchChoices("", listOf("main", " ", LOADING_BRANCH)))
    }

    @Test
    fun `current state preset block reason covers missing main and incomplete repos`() {
        assertEquals(CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE, currentStatePresetBlockReason(null, emptyList()))
        assertEquals(CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE, currentStatePresetBlockReason("", emptyList()))
        assertEquals(CurrentStatePresetBlockReason.INCOMPLETE_REPOSITORIES, currentStatePresetBlockReason("main", listOf("SubA")))
        assertNull(currentStatePresetBlockReason("main", emptyList()))
    }

    @Test
    fun `main branch status text appends the dirty suffix only when dirty`() {
        assertFalse(mainBranchStatusText("dev", false).contains(Bundle.msg("status.tooltip.dirty")))
        assertTrue(mainBranchStatusText("dev", true).contains(Bundle.msg("status.tooltip.dirty")))
    }

    @Test
    fun `preview current cell text covers detached missing and probe error`() {
        assertEquals(Bundle.msg("status.detached"), currentBranchCellText(row(current = null)))
        assertEquals(Bundle.msg("status.missing.dir"), currentBranchCellText(row(exists = false)))
        assertEquals("boom", currentBranchCellText(row(probeError = "boom")))
        assertEquals("dev", currentBranchCellText(row(current = "dev")))
    }

    @Test
    fun `preview dirty cell text covers clean unknown and collision counts`() {
        assertEquals(Bundle.msg("status.clean"), dirtyCellText(row(dirtyCount = 0)))
        assertEquals("?", dirtyCellText(row(dirtyCount = -1)))
        assertEquals(Bundle.msg("status.file.count", 3), dirtyCellText(row(dirtyCount = 3)))
        assertEquals(
            Bundle.msg("status.file.count.collision", 3, 2),
            dirtyCellText(row(dirtyCount = 3, untrackedCollisions = setOf("a", "b"))),
        )
        assertEquals("—", dirtyCellText(row(exists = false)))
    }

    @Test
    fun `preview source cell text covers both local remote and none`() {
        assertEquals(Bundle.msg("status.both"), sourceCellText(row()))
        assertEquals(Bundle.msg("status.local.only"), sourceCellText(row(hasRemote = false)))
        assertEquals(Bundle.msg("status.remote.only"), sourceCellText(row(hasLocal = false)))
        assertEquals(Bundle.msg("status.none"), sourceCellText(row(hasLocal = false, hasRemote = false)))
        assertEquals("—", sourceCellText(row(exists = false)))
    }

    @Test
    fun `preview tones map missing dirty and switching states to the right tone`() {
        assertEquals(PreviewCellTone.WARN, currentBranchCellTone(row(exists = false)))
        assertEquals(PreviewCellTone.MUTED, currentBranchCellTone(row(current = "main", target = "main")))
        assertEquals(PreviewCellTone.NORMAL, currentBranchCellTone(row()))
        assertEquals(PreviewCellTone.ACCENT, targetCellTone(row()))
        assertEquals(PreviewCellTone.WARN, targetCellTone(row(hasLocal = false, hasRemote = false)))
        assertEquals(PreviewCellTone.MUTED, dirtyCellTone(row(dirtyCount = 0)))
        assertEquals(PreviewCellTone.WARN, dirtyCellTone(row(dirtyCount = 3)))
        assertEquals(PreviewCellTone.MUTED, sourceCellTone(row(exists = false)))
        assertEquals(PreviewCellTone.NORMAL, sourceCellTone(row()))
    }

    private fun row(
        exists: Boolean = true,
        current: String? = "dev",
        target: String = "main",
        dirtyCount: Int = 0,
        hasLocal: Boolean = true,
        hasRemote: Boolean = true,
        probeError: String? = null,
        untrackedCollisions: Set<String> = emptySet(),
    ) = PreflightRow("repo", "path", target, exists, current, dirtyCount, hasLocal, hasRemote, probeError, untrackedCollisions)

    private object NonTextTransferable : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = error("not needed")
    }
}
