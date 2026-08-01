package com.submodule.branchswitcher.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.border.AbstractBorder

internal data class PopupAction(
    val text: String,
    val icon: Icon,
    val danger: Boolean = false,
    val perform: () -> Unit,
)

/** Shows the compact, right-aligned overflow menu defined by the Tool Window design. */
internal fun showActionPopup(anchor: JComponent, sections: List<List<PopupAction>>) {
    val nonEmptySections = sections.filter { it.isNotEmpty() }
    if (nonEmptySections.isEmpty()) return

    dismissToolTip(anchor)
    val menu = DesignPopupMenu()
    nonEmptySections.forEachIndexed { index, actions ->
        if (index > 0) menu.addSectionSeparator()
        actions.forEach(menu::addAction)
    }
    menu.installKeyboardNavigation()
    menu.show(
        anchor,
        anchor.width - menu.preferredSize.width,
        anchor.height + JBUI.scale(4),
    )
}

private class DesignPopupMenu : JPopupMenu() {
    private val actionButtons = mutableListOf<PopupMenuButton>()
    private var focusedIndex = -1

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = RoundedPopupBorder()
    }

    override fun getPreferredSize(): Dimension {
        val naturalSize = super.getPreferredSize()
        return Dimension(
            naturalSize.width.coerceIn(JBUI.scale(220), JBUI.scale(360)),
            naturalSize.height,
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2d = graphics.create() as Graphics2D
        graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2d.color = popupBackground()
        graphics2d.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))
        graphics2d.dispose()
    }

    fun addAction(action: PopupAction) {
        val button = PopupMenuButton(action)
        button.addActionListener {
            isVisible = false
            action.perform()
        }
        actionButtons.add(button)
        add(button)
    }

    fun addSectionSeparator() {
        add(PopupMenuSeparator())
    }

    fun installKeyboardNavigation() {
        val inputMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "popup.next")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "popup.previous")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "popup.close")
        actionMap.put("popup.next", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                focusRelative(1)
            }
        })
        actionMap.put("popup.previous", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                focusRelative(-1)
            }
        })
        actionMap.put("popup.close", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                isVisible = false
            }
        })
    }

    private fun focusRelative(delta: Int) {
        if (actionButtons.isEmpty()) return
        focusedIndex = if (focusedIndex < 0) {
            if (delta > 0) 0 else actionButtons.lastIndex
        } else {
            (focusedIndex + delta + actionButtons.size) % actionButtons.size
        }
        actionButtons[focusedIndex].requestFocusInWindow()
    }
}

private class PopupMenuButton(action: PopupAction) : JButton(action.text, action.icon) {
    init {
        alignmentX = LEFT_ALIGNMENT
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.CENTER
        horizontalTextPosition = SwingConstants.RIGHT
        font = JBFont.regular()
        foreground = if (action.danger) NamedColorUtil.getErrorForeground() else JBColor.foreground()
        border = JBUI.Borders.empty(5, 8)
        margin = JBUI.emptyInsets()
        iconTextGap = JBUI.scale(9)
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        val naturalWidth = getFontMetrics(font).stringWidth(action.text) +
            (action.icon.iconWidth.coerceAtLeast(0)) + iconTextGap + JBUI.scale(32)
        preferredSize = Dimension(maxOf(JBUI.scale(208), naturalWidth), JBUI.scale(30))
        minimumSize = Dimension(0, JBUI.scale(30))
        maximumSize = Dimension(Short.MAX_VALUE.toInt(), JBUI.scale(30))
        toolTipText = action.text
    }

    override fun paintComponent(graphics: Graphics) {
        if (model.isRollover || isFocusOwner) {
            val graphics2d = graphics.create() as Graphics2D
            graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics2d.color = popupSelectionBackground()
            graphics2d.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            graphics2d.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class PopupMenuSeparator : JComponent() {
    init {
        alignmentX = LEFT_ALIGNMENT
        preferredSize = Dimension(JBUI.scale(208), JBUI.scale(9))
        minimumSize = Dimension(0, JBUI.scale(9))
        maximumSize = Dimension(Short.MAX_VALUE.toInt(), JBUI.scale(9))
    }

    override fun paintComponent(graphics: Graphics) {
        graphics.color = popupSeparatorColor()
        val y = height / 2
        graphics.drawLine(JBUI.scale(6), y, width - JBUI.scale(6), y)
    }
}

private class RoundedPopupBorder : AbstractBorder() {
    override fun getBorderInsets(component: java.awt.Component): Insets = JBUI.insets(6)

    override fun paintBorder(
        component: java.awt.Component,
        graphics: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val graphics2d = graphics.create() as Graphics2D
        graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2d.color = popupBorderColor()
        graphics2d.drawRoundRect(x, y, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
        graphics2d.dispose()
    }
}

private fun popupBackground(): Color = JBColor(0xFFFFFF, 0x2B2D30)

private fun popupSelectionBackground(): Color = JBColor(0xE8EAED, 0x3A3D43)

private fun popupBorderColor(): Color = JBColor(0xC8CBD0, 0x4A4D53)

private fun popupSeparatorColor(): Color = JBColor(0xD9DBDF, 0x45484E)

private fun dismissToolTip(anchor: JComponent) {
    ToolTipManager.sharedInstance().mouseExited(
        MouseEvent(
            anchor,
            MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(),
            0,
            -1,
            -1,
            0,
            false,
        ),
    )
}
