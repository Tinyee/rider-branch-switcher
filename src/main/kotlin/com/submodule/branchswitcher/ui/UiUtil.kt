package com.submodule.branchswitcher.ui

import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.SwingUtilities

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
