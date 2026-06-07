package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.switch.shortLabel
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Pure-logic tests for SubmoduleRowManager data classes and utility functions.
 * No IntelliJ runtime needed.
 */
class SubmoduleRowManagerTest {

    // ---- SubRow data class ----

    @Test
    fun `SubRow stores all fields correctly`() {
        val combo = JComboBox<String>()
        val panel = JPanel()
        val dot = JLabel("●")
        val row = SubmoduleRowManager.SubRow(
            path = "SubA",
            combo = combo,
            panel = panel,
            deleted = false,
            loaded = true,
            targetBranch = "dev",
            statusDot = dot,
        )
        assertEquals("SubA", row.path)
        assertEquals(combo, row.combo)
        assertEquals(panel, row.panel)
        assertFalse(row.deleted)
        assertTrue(row.loaded)
        assertEquals("dev", row.targetBranch)
        assertEquals(dot, row.statusDot)
    }

    @Test
    fun `SubRow targetBranch defaults to empty`() {
        val row = SubmoduleRowManager.SubRow("p", JComboBox<String>(), JPanel())
        assertEquals("", row.targetBranch)
    }

    @Test
    fun `SubRow deleted flag can be toggled`() {
        val row = SubmoduleRowManager.SubRow("p", JComboBox<String>(), JPanel())
        assertFalse(row.deleted)
        row.deleted = true
        assertTrue(row.deleted)
    }

    // ---- shortLabel utility ----

    @Test
    fun `shortLabel returns last segment`() {
        assertEquals("SubA", shortLabel("lib/SubA"))
        assertEquals("SubB", shortLabel("external/SubB"))
        assertEquals("single", shortLabel("single"))
    }

    @Test
    fun `shortLabel strips trailing tilde`() {
        assertEquals("SubA", shortLabel("lib/SubA~"))
    }
}
