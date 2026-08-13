package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.components.JBLabel
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.ToolWindowLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.gitRootPath
import com.submodule.branchswitcher.presentation.ShortcutPresetLoadDecision
import com.submodule.branchswitcher.presentation.shortcutPresetLoadDecision
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.ui.SwitchFlowCoordinator
import com.submodule.branchswitcher.ui.SwitchPreviewDialog
import com.submodule.branchswitcher.ui.invokeLaterIfAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

class SwitchPresetAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.isDisposed == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dataContext = e.dataContext
        val service = project.service<BranchSwitcherService>()
        service.scope.launch {
            val loadResult = service.loadPresets()
            val presets = service.presets.toList()
            project.invokeLaterIfAlive {
                choosePreset(project, service, loadResult, presets, dataContext)
            }
        }
    }

    private fun choosePreset(
        project: Project,
        service: BranchSwitcherService,
        loadResult: Result<*>,
        presets: List<Preset>,
        dataContext: DataContext,
    ) {
        when (shortcutPresetLoadDecision(loadResult.isSuccess, presets.size)) {
            ShortcutPresetLoadDecision.LoadFailed -> {
                Notifier.error(project, Bundle.msg("preset.load.failed"),
                    loadResult.exceptionOrNull()?.message ?: Bundle.msg("dialog.import.failed"))
                return
            }
            ShortcutPresetLoadDecision.NoPresets -> {
                Messages.showInfoMessage(project, Bundle.msg("action.no.presets"), Bundle.msg("plugin.title"))
                return
            }
            ShortcutPresetLoadDecision.Ready -> Unit
        }
        // Filterable chooser with a per-row branch summary; replaces the old
        // wall-of-buttons Messages.showDialog picker as presets grow.
        val list = JList(presets.toTypedArray()).apply {
            cellRenderer = PresetChooserCellRenderer()
            selectedIndex = 0
        }
        PopupChooserBuilder(list)
            .setTitle(Bundle.msg("preset.chooser.title"))
            // The builder sizes its own scroll pane from its own visibleRowCount
            // (default 15); setting it on the JList is overridden, so set it here.
            .setVisibleRowCount(presets.size.coerceIn(1, MAX_VISIBLE_ROWS))
            .setNamerForFiltering { preset: Preset -> preset.name }
            .setItemChosenCallback(Consumer { preset: Preset -> executeSwitch(project, service, preset) })
            .setMovable(false)
            .setResizable(true)
            .setAutoSelectIfEmpty(true)
            .createPopup()
            .showInBestPositionFor(dataContext)
    }

    @Suppress("TooGenericExceptionCaught") // shortcut entry reports all non-cancellation workflow failures consistently
    private fun executeSwitch(project: Project, service: BranchSwitcherService, preset: Preset) {
        val root = project.gitRootPath() ?: run {
            Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
            return
        }
        val collector = actionLogger(project)
        val coordinator = SwitchFlowCoordinator(project, service)
        val operationContext = newOperationContext("switch")
        service.scope.launch(Dispatchers.Default) {
            val probeResult = try {
                coordinator.preflight(root, preset, collector, operationContext)
            } catch (_: CancellationException) {
                return@launch
            } catch (_: ProcessCanceledException) {
                return@launch
            } catch (e: Exception) {
                collector.logFailure("preflight probe failed", e)
                project.invokeLaterIfAlive {
                    Notifier.error(project, Bundle.msg("notify.preflight.failed"),
                        Bundle.msg("notify.preflight.failed.msg", e.javaClass.simpleName, e.message ?: ""))
                }
                return@launch
            }
            project.invokeLaterIfAlive {
                // Same rich preview confirmation as the sidebar entry, so both
                // switch paths share one confirmation UI.
                val request = service.resolveSwitchRequest(preset)
                if (!SwitchPreviewDialog.showAndConfirm(project, request, probeResult)) {
                    collector.warn("switch cancelled by user - preview declined")
                    return@invokeLaterIfAlive
                }
                val preApproved = coordinator.resolvePreApprovedSubmoduleInit(request, probeResult)
                    ?: run {
                        collector.warn("switch cancelled by user - submodule init declined")
                        return@invokeLaterIfAlive
                    }
                coordinator.executeAndNotify(root, request, collector, operationContext,
                    preApprovedSubmoduleInit = preApproved) {
                    project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
                }
            }
        }
    }

    private fun actionLogger(project: Project): AppLogger = ToolWindowLogger { entry ->
        project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onLog(entry)
    }

    /** Two-line chooser row: preset name over a muted branch summary. */
    private class PresetChooserCellRenderer : DefaultListCellRenderer() {
        private val nameLabel = JBLabel().apply {
            border = JBUI.Borders.empty(2, 8, 0, 8)
        }
        private val summaryLabel = JBLabel().apply {
            border = JBUI.Borders.empty(0, 8, 3, 8)
            font = font.deriveFont((font.size2D - 1f).coerceAtLeast(10f))
        }
        private val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            add(nameLabel)
            add(summaryLabel)
        }

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val preset = value as? Preset ?: return row
            val background = if (isSelected) UIUtil.getListSelectionBackground(true) else list.background
            val foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else list.foreground
            nameLabel.text = preset.name
            nameLabel.foreground = foreground
            summaryLabel.text = presetSummary(preset)
            summaryLabel.foreground = if (isSelected) foreground else UIUtil.getContextHelpForeground()
            row.background = background
            return row
        }
    }

    private companion object {
        const val MAX_VISIBLE_ROWS = 12
    }
}

/** Muted secondary line under each preset name in the chooser. */
private fun presetSummary(preset: Preset): String =
    Bundle.msg("preset.chooser.summary", preset.main, preset.submodules.size + 1)
