package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/**
 * Extension that clears keyboard focus after button action or mouse release,
 * preventing unsightly focus rings on toolbar buttons in IntelliJ LaF.
 * Uses [ApplicationManager.invokeLater] for correct modality integration.
 */
internal fun JButton.noFocusRing(): JButton = apply {
    addActionListener {
        ApplicationManager.getApplication().invokeLater { releaseFocus() }
    }
    addMouseListener(object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            if (!contains(e.point)) {
                ApplicationManager.getApplication().invokeLater { releaseFocus() }
            }
        }
    })
}

private fun releaseFocus() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
}
