package com.submodule.branchswitcher.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.service.BranchSwitcherService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * IDE Settings entry for File → Settings → Version Control → Submodule Branch Switcher.
 * Mirrors the options available in the tool window, persisted via [BranchSwitcherService].
 */
class BranchSwitcherConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private var dirtyCombo: JComboBox<String>? = null
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

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JPanel.LEFT_ALIGNMENT
        }

        form.add(JLabel(Bundle.msg("label.dirty.working.tree")).apply {
            border = JBUI.Borders.empty(0, 0, 2, 0)
        })
        form.add(dirtyCombo!!.apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            maximumSize = Dimension(JBUI.scale(360), preferredSize.height)
        })

        form.add(JLabel(Bundle.msg("option.timeout")).apply {
            border = JBUI.Borders.empty(12, 0, 2, 0)
        })
        form.add(timeoutCombo!!.apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            maximumSize = Dimension(JBUI.scale(180), preferredSize.height)
        })

        form.add(fetchCheck!!.apply {
            border = JBUI.Borders.empty(14, 0, 0, 0)
            alignmentX = JPanel.LEFT_ALIGNMENT
        })
        form.add(pullCheck!!.apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
        })
        form.add(confirmInitCheck!!.apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
        })

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
        timeoutCombo?.selectedIndex = timeoutToIndex(s.timeoutSeconds)
        fetchCheck?.isSelected = s.fetchFirst
        pullCheck?.isSelected = s.pullAfterSwitch
        confirmInitCheck?.isSelected = s.confirmBeforeInit
    }

    override fun disposeUIResources() {
        panel = null
        dirtyCombo = null
        timeoutCombo = null
        fetchCheck = null
        pullCheck = null
        confirmInitCheck = null
    }

    private fun dirtyComboIndex(): Int = dirtyCombo?.selectedIndex ?: 0
    private fun timeoutComboIndex(): Int = timeoutCombo?.selectedIndex ?: 1

    companion object {
        private fun dirtyActionToIndex(a: DirtyAction) = when (a) {
            DirtyAction.Stash -> 0
            DirtyAction.Skip -> 1
            DirtyAction.Force -> 2
        }
        private fun indexToDirtyAction(i: Int) = when (i) {
            1 -> DirtyAction.Skip
            2 -> DirtyAction.Force
            else -> DirtyAction.Stash
        }
        private fun timeoutToIndex(s: Int) = when (s) {
            30 -> 0
            120 -> 2
            300 -> 3
            else -> 1
        }
        private fun indexToTimeout(i: Int) = when (i) {
            0 -> 30
            2 -> 120
            3 -> 300
            else -> 60
        }
    }
}
