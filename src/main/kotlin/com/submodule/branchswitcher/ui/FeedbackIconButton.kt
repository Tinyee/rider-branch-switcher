package com.submodule.branchswitcher.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.Timer

/**
 * Toolbar icon button with hover and pressed feedback.
 *
 * Plain [JButton]s in the IntelliJ LAF show no pressed state for borderless
 * buttons, so feedback is drawn here: a translucent rounded fill on hover,
 * a deeper fill while pressed. Rollover tracking must be enabled explicitly,
 * otherwise [javax.swing.ButtonModel.isRollover] never updates.
 */
internal class FeedbackIconButton(icon: Icon) : JButton(icon) {
    private var flashTimer: Timer? = null
    private var flashOriginalIcon: Icon? = null
    private var flashOriginalToolTip: String? = null

    init {
        applyToolbarButtonStyle(this)
    }

    override fun paintComponent(g: Graphics) {
        val feedback = when {
            model.isPressed -> PRESSED_COLOR
            model.isRollover -> HOVER_COLOR
            else -> null
        }
        if (feedback != null) paintFeedbackBackground(g, feedback, width, height)
        super.paintComponent(g)
    }

    /**
     * Temporarily swaps the icon (and optionally the tooltip) as action
     * confirmation; both are restored after [durationMs]. A repeated flash
     * restarts the timer with the latest content.
     */
    fun flashIcon(
        replacement: Icon,
        durationMs: Long = FLASH_DURATION_MS,
        replacementToolTip: String? = null,
    ) {
        if (flashTimer == null) {
            flashOriginalIcon = icon
            flashOriginalToolTip = toolTipText
        } else {
            flashTimer?.stop()
        }
        icon = replacement
        if (replacementToolTip != null) toolTipText = replacementToolTip
        val timer = Timer(durationMs.toInt()) { restoreFlash() }
        timer.isRepeats = false
        flashTimer = timer
        timer.start()
    }

    internal fun restoreFlash() {
        flashTimer?.stop()
        flashTimer = null
        icon = flashOriginalIcon
        toolTipText = flashOriginalToolTip
    }

    companion object {
        private const val FLASH_DURATION_MS = 1_200L
    }
}

/** Same feedback as [FeedbackIconButton], with a persistent fill while selected. */
internal class FeedbackIconToggleButton(icon: Icon) : JToggleButton(icon) {
    init {
        applyToolbarButtonStyle(this)
    }

    override fun paintComponent(g: Graphics) {
        val feedback = when {
            model.isPressed || model.isSelected -> PRESSED_COLOR
            model.isRollover -> HOVER_COLOR
            else -> null
        }
        if (feedback != null) paintFeedbackBackground(g, feedback, width, height)
        super.paintComponent(g)
    }
}

private fun applyToolbarButtonStyle(button: AbstractButton) {
    button.isFocusable = false
    button.isRolloverEnabled = true
    button.isOpaque = false
    // Let the LAF skip its own background painting entirely (DarculaButtonUI
    // returns early when contentAreaFilled is false); the button shows only our
    // self-drawn hover/pressed feedback, like IntelliJ's own toolbar icon buttons.
    button.isContentAreaFilled = false
    button.margin = JBUI.emptyInsets()
    button.border = JBUI.Borders.empty()
    button.preferredSize = Dimension(JBUI.scale(26), JBUI.scale(24))
}

private fun paintFeedbackBackground(g: Graphics, color: JBColor, width: Int, height: Int) {
    val g2 = g.create() as? Graphics2D ?: return
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        val arc = JBUI.scale(6)
        g2.fillRoundRect(0, 0, width, height, arc, arc)
    } finally {
        g2.dispose()
    }
}

private val HOVER_COLOR = JBColor(0x00000012, 0xFFFFFF14.toInt())
private val PRESSED_COLOR = JBColor(0x0000001E, 0xFFFFFF28.toInt())
