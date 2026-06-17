package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton

/**
 * Factory that creates a [JButton] with focus-ring suppression already applied.
 * Prefer this over manual `JButton(...).noFocusRing()` to guarantee consistency.
 *
 * Usage: `jButton(Bundle.msg("action.save"), AllIcons.Actions.MenuSaveall) { isEnabled = false }`
 */
fun jButton(text: String = "", icon: Icon? = null, init: JButton.() -> Unit = {}): JButton =
    JButton(text, icon).apply(init).noFocusRing()

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

/**
 * Post [action] to the EDT. If [this] project is disposed when the event fires,
 * the action is silently dropped. This prevents UI operations on a disposed project
 * (e.g., after IDE shutdown), which would otherwise throw.
 *
 * Equivalent to the pattern:
 *   ApplicationManager.getApplication().invokeLater({
 *       if (project.isDisposed) return@invokeLater
 *       action()
 *   }, ModalityState.any(), project.disposed)
 */
fun Project.invokeLaterIfAlive(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater({
        if (isDisposed) return@invokeLater
        action()
    }, ModalityState.any(), disposed)
}
