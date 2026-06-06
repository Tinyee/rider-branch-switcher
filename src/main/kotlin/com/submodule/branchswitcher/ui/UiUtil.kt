package com.submodule.branchswitcher.ui

import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * Extension that clears keyboard focus after button action or mouse release,
 * preventing unsightly focus rings on toolbar buttons in IntelliJ LaF.
 */
internal fun JButton.noFocusRing(): JButton = apply {
    addActionListener {
        SwingUtilities.invokeLater { releaseFocus() }
    }
    addMouseListener(object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            if (!contains(e.point)) releaseFocus()
        }
    })
}

private fun releaseFocus() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
}
