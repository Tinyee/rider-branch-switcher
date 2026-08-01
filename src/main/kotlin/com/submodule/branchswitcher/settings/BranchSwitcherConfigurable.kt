package com.submodule.branchswitcher.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.ui.withCompactHeight
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * IDE Settings entry for File → Settings → Version Control → Submodule Branch Switcher.
 * Mirrors the options available in the tool window, persisted via [BranchSwitcherService].
 */
class BranchSwitcherConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private var dirtyCombo: JComboBox<String>? = null
    private var dirtyDescription: JLabel? = null
    private var timeoutCombo: JComboBox<String>? = null
    private var fetchCheck: JCheckBox? = null
    private var pullCheck: JCheckBox? = null
    private var confirmInitCheck: JCheckBox? = null

    private val service get() = project.service<BranchSwitcherService>()

    override fun getDisplayName(): String = Bundle.msg("plugin.title")

    override fun createComponent(): JComponent {
        dirtyCombo = JComboBox(arrayOf(
            Bundle.msg("option.dirty.stash"),
            Bundle.msg("option.dirty.skip"),
            Bundle.msg("option.dirty.force"),
        ))
        timeoutCombo = JComboBox(arrayOf("30s", "60s", "120s", "300s"))
        fetchCheck = JCheckBox(Bundle.msg("option.fetch.before"))
        pullCheck = JCheckBox(Bundle.msg("option.pull.after"))
        confirmInitCheck = JCheckBox(Bundle.msg("option.confirm.init"))
        dirtyDescription = createDescriptionLabel()
        dirtyCombo?.addActionListener { updateDirtyDescription() }

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        form.add(createDescriptionLabel("settings.scope.description", bottomInset = 12))
        form.add(createFieldLabel("label.dirty.working.tree"))
        form.add(prepareComboBox(dirtyCombo!!, DIRTY_CONTROL_WIDTH))
        form.add(dirtyDescription!!.apply {
            border = JBUI.Borders.empty(3, 0, 12, 0)
        })

        form.add(createFieldLabel("option.timeout"))
        form.add(prepareComboBox(timeoutCombo!!, TIMEOUT_CONTROL_WIDTH))
        form.add(createDescriptionLabel("settings.timeout.description", bottomInset = 12))

        form.add(prepareCheckBox(fetchCheck!!, topInset = 2))
        form.add(createDescriptionLabel(
            "settings.fetch.description",
            leftInset = CHECKBOX_DESCRIPTION_INDENT,
            bottomInset = 8,
        ))
        form.add(prepareCheckBox(pullCheck!!))
        form.add(createDescriptionLabel(
            "settings.pull.description",
            leftInset = CHECKBOX_DESCRIPTION_INDENT,
            bottomInset = 8,
        ))
        form.add(prepareCheckBox(confirmInitCheck!!))
        form.add(createDescriptionLabel(
            "settings.confirm.init.description",
            leftInset = CHECKBOX_DESCRIPTION_INDENT,
        ))

        updateDirtyDescription()

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12, 12, 12, 12)
            add(form, BorderLayout.NORTH)
        }
        this.panel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val s = service
        return dirtyComboIndex() != dirtyActionToIndex(s.dirtyAction) ||
            timeoutComboIndex() != timeoutToIndex(s.timeoutSeconds) ||
            fetchCheck?.isSelected != s.fetchFirst ||
            pullCheck?.isSelected != s.pullAfterSwitch ||
            confirmInitCheck?.isSelected != s.confirmBeforeInit
    }

    override fun apply() {
        val s = service
        s.dirtyAction = indexToDirtyAction(dirtyComboIndex())
        s.timeoutSeconds = indexToTimeout(timeoutComboIndex())
        fetchCheck?.let { s.fetchFirst = it.isSelected }
        pullCheck?.let { s.pullAfterSwitch = it.isSelected }
        confirmInitCheck?.let { s.confirmBeforeInit = it.isSelected }
    }

    override fun reset() {
        val s = service
        dirtyCombo?.selectedIndex = dirtyActionToIndex(s.dirtyAction)
        updateDirtyDescription()
        timeoutCombo?.selectedIndex = timeoutToIndex(s.timeoutSeconds)
        fetchCheck?.isSelected = s.fetchFirst
        pullCheck?.isSelected = s.pullAfterSwitch
        confirmInitCheck?.isSelected = s.confirmBeforeInit
    }

    override fun disposeUIResources() {
        panel = null
        dirtyCombo = null
        dirtyDescription = null
        timeoutCombo = null
        fetchCheck = null
        pullCheck = null
        confirmInitCheck = null
    }

    private fun dirtyComboIndex(): Int = dirtyCombo?.selectedIndex ?: 0
    private fun timeoutComboIndex(): Int = timeoutCombo?.selectedIndex ?: 1

    private fun updateDirtyDescription() {
        val messageKey = when (dirtyComboIndex()) {
            1 -> "settings.dirty.skip.description"
            2 -> "settings.dirty.force.description"
            else -> "settings.dirty.stash.description"
        }
        dirtyDescription?.text = descriptionHtml(Bundle.msg(messageKey))
    }

    private fun createFieldLabel(messageKey: String): JLabel = JLabel(Bundle.msg(messageKey)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, 0, 3, 0)
    }

    private fun <T> prepareComboBox(comboBox: JComboBox<T>, width: Int): JComboBox<T> =
        comboBox.withCompactHeight(JBUI.scale(width)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun prepareCheckBox(checkBox: JCheckBox, topInset: Int = 0): JCheckBox = checkBox.apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(topInset, 0, 0, 0)
    }

    private fun createDescriptionLabel(
        messageKey: String? = null,
        leftInset: Int = 0,
        bottomInset: Int = 0,
    ): JLabel = JLabel().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = UIManager.getColor("Label.disabledForeground") ?: foreground
        border = JBUI.Borders.empty(2, leftInset, bottomInset, 0)
        if (messageKey != null) {
            text = descriptionHtml(Bundle.msg(messageKey))
        }
    }

    private fun descriptionHtml(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<html><div width='${JBUI.scale(DESCRIPTION_WIDTH)}'>$escaped</div></html>"
    }

    private companion object {
        const val DIRTY_CONTROL_WIDTH = 420
        const val TIMEOUT_CONTROL_WIDTH = 180
        const val DESCRIPTION_WIDTH = 560
        const val CHECKBOX_DESCRIPTION_INDENT = 24
    }

}
