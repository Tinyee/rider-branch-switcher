package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
