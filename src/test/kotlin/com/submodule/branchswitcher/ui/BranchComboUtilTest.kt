package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import javax.swing.JComboBox
import javax.swing.JTextField

class BranchComboUtilTest {

    @Test
    fun `branch popup filter is case insensitive and preserves typed text`() {
        val combo = JComboBox(arrayOf("main", "feature/login", "Feature/Search"))
        combo.isEditable = true
        combo.putClientProperty(KEY_ALL_BRANCHES, listOf("main", "feature/login", "Feature/Search"))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = "FEATURE"
        editor.caretPosition = editor.text.length

        filterBranchPopup(combo, editor)

        assertEquals(2, combo.itemCount)
        assertEquals("feature/login", combo.getItemAt(0))
        assertEquals("Feature/Search", combo.getItemAt(1))
        assertEquals("FEATURE", editor.text)
        assertEquals(editor.text.length, editor.caretPosition)
    }

    @Test
    fun `branch popup filter restores full list for blank text`() {
        val combo = JComboBox(arrayOf("feature/login"))
        combo.isEditable = true
        combo.putClientProperty(KEY_ALL_BRANCHES, listOf("main", "dev", "feature/login"))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = ""

        filterBranchPopup(combo, editor)

        assertEquals(listOf("main", "dev", "feature/login"), (0 until combo.itemCount).map(combo::getItemAt))
    }

    @Test
    fun `branch choices add missing current branch and remove duplicates`() {
        val result = mergeBranchChoices("feature/current", listOf("main", "main", "dev"))

        assertEquals(listOf("feature/current", "main", "dev"), result)
    }

    @Test
    fun `branch choices never expose loading placeholder or blanks`() {
        val result = mergeBranchChoices(LOADING_BRANCH, listOf("", " ", LOADING_BRANCH, "main"))

        assertEquals(listOf("main"), result)
        assertFalse(result.contains(LOADING_BRANCH))
    }

    @Test
    fun `branch choices keep existing current position`() {
        val result = mergeBranchChoices("dev", listOf("main", "dev", "feature"))

        assertEquals(listOf("main", "dev", "feature"), result)
    }

    @Test
    fun `branch load restores combo and completes loading`() {
        val combo = displayableCombo()
        var starts = 0
        var ends = 0

        loadComboBranches(
            combo, File("."), "dev", branchGit { listOf("main", "dev") },
            CoroutineScope(Dispatchers.Unconfined), createStringAppender {},
            onLoadStart = { starts++ },
            onLoadEnd = { ends++ },
            scheduleUi = { it() },
        )

        assertEquals(1, starts)
        assertEquals(1, ends)
        assertTrue(combo.isEnabled)
        assertEquals(listOf("main", "dev"), (0 until combo.itemCount).map(combo::getItemAt))
    }

    @Test
    fun `branch load exception restores combo and completes loading`() {
        val combo = displayableCombo()
        val logs = mutableListOf<String>()
        var ends = 0

        loadComboBranches(
            combo, File("."), "dev", branchGit { error("broken") },
            CoroutineScope(Dispatchers.Unconfined), createStringAppender { logs += it },
            onLoadStart = {},
            onLoadEnd = { ends++ },
            scheduleUi = { it() },
        )

        assertEquals(1, ends)
        assertTrue(combo.isEnabled)
        assertEquals(listOf("dev"), (0 until combo.itemCount).map(combo::getItemAt))
        assertTrue(logs.any { it.contains("loadBranches failed") })
    }

    @Test
    fun `disposed combo is not modified but completes loading`() {
        val combo = displayableCombo(displayable = false)
        var ends = 0

        loadComboBranches(
            combo, File("."), "dev", branchGit { listOf("main") },
            CoroutineScope(Dispatchers.Unconfined), createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { ends++ },
            scheduleUi = { it() },
        )

        assertEquals(1, ends)
        assertFalse(combo.isEnabled)
        assertEquals(listOf(LOADING_BRANCH), (0 until combo.itemCount).map(combo::getItemAt))
    }

    private fun displayableCombo(displayable: Boolean = true): JComboBox<String> =
        object : JComboBox<String>() {
            override fun isDisplayable(): Boolean = displayable
        }

    private fun branchGit(load: () -> List<String>): GitClient =
        Proxy.newProxyInstance(
            GitClient::class.java.classLoader,
            arrayOf(GitClient::class.java),
        ) { _, method, _ ->
            if (method.name == "listAllBranches") load() else null
        } as GitClient
}
