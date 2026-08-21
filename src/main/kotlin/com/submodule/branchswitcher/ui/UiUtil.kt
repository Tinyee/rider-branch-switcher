package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton

/**
 * Amber warning tone shared by the panel, preset editor, and log panel. Single
 * source of truth so a palette change cannot leave one consumer stale.
 */
internal val WARN_AMBER: JBColor = JBColor(0xE07B00, 0xFFA726)

/** Default [scheduleUi] implementation: posts [action] to the EDT. */
internal val edtSchedule: ((() -> Unit) -> Unit) = { action ->
    ApplicationManager.getApplication().invokeLater(action)
}

/**
 * Creates the "..." more-actions button shared by the panel header and the preset
 * editor cards, so their sizing and tooltip cannot drift apart.
 */
internal fun moreIconButton(onClick: (JButton) -> Unit): JButton =
    jButton(icon = AllIcons.Actions.MoreHorizontal) {
        margin = JBUI.insets(0, 4, 0, 4)
        preferredSize = Dimension(JBUI.scale(32), preferredSize.height)
        maximumSize = preferredSize
        minimumSize = preferredSize
        toolTipText = Bundle.msg("action.more.tip")
        addActionListener { onClick(this) }
    }

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
