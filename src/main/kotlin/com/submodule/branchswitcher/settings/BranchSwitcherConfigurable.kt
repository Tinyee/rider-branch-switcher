package com.submodule.branchswitcher.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.ui.withCompactHeight
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
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
    private var telemetryCheck: JCheckBox? = null

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
        form.add(dirtyCombo!!.withCompactHeight(JBUI.scale(360)).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
        })

        form.add(JLabel(Bundle.msg("option.timeout")).apply {
            border = JBUI.Borders.empty(12, 0, 2, 0)
        })
        form.add(timeoutCombo!!.withCompactHeight(JBUI.scale(180)).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
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

        telemetryCheck = JCheckBox(Bundle.msg("telemetry.optin.label"))
        form.add(telemetryCheck!!.apply {
            border = JBUI.Borders.empty(16, 0, 0, 0)
            alignmentX = JPanel.LEFT_ALIGNMENT
        })

        // Copy stats button
        val copyBtn = JButton(Bundle.msg("telemetry.copy.stats")).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            addActionListener {
                val stats = service.exportTelemetry()
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(stats), null)
            }
        }
        form.add(copyBtn.apply { border = JBUI.Borders.empty(4, 0, 0, 0) })

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
            confirmInitCheck?.isSelected != s.confirmBeforeInit ||
            telemetryCheck?.isSelected != s.telemetryOptIn
    }

    override fun apply() {
        val s = service
        s.dirtyAction = indexToDirtyAction(dirtyComboIndex())
        s.timeoutSeconds = indexToTimeout(timeoutComboIndex())
        fetchCheck?.let { s.fetchFirst = it.isSelected }
        pullCheck?.let { s.pullAfterSwitch = it.isSelected }
        confirmInitCheck?.let { s.confirmBeforeInit = it.isSelected }
        telemetryCheck?.let { s.telemetryOptIn = it.isSelected }
    }

    override fun reset() {
        val s = service
        dirtyCombo?.selectedIndex = dirtyActionToIndex(s.dirtyAction)
        timeoutCombo?.selectedIndex = timeoutToIndex(s.timeoutSeconds)
        fetchCheck?.isSelected = s.fetchFirst
        pullCheck?.isSelected = s.pullAfterSwitch
        confirmInitCheck?.isSelected = s.confirmBeforeInit
        telemetryCheck?.isSelected = s.telemetryOptIn
    }

    override fun disposeUIResources() {
        panel = null
        dirtyCombo = null
        timeoutCombo = null
        fetchCheck = null
        pullCheck = null
        confirmInitCheck = null
        telemetryCheck = null
    }

    private fun dirtyComboIndex(): Int = dirtyCombo?.selectedIndex ?: 0
    private fun timeoutComboIndex(): Int = timeoutCombo?.selectedIndex ?: 1

}
