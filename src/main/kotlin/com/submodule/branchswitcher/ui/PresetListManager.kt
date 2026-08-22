package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.refreshVcsTail
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.workflow.SingleRepositorySwitcher
import java.awt.BorderLayout
import java.awt.Font
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Owns the preset editor list, its Swing container, and empty-state rendering.
 * Collection commands are delegated to [PresetCollectionActions].
 */
internal class PresetListManager(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val presetsInner: JPanel,
    private val onSwitch: (Preset) -> Unit,
    private val onDerive: (Path, Preset, String) -> Unit,
    private val onStateChanged: () -> Unit,
    /** Claimed/released on the UI thread while the single-repository switch path runs a write. */
    private val onBusyChange: ((Boolean) -> Unit)? = null,
) : PresetCollectionHost {
    private val mutableEditors = mutableListOf<PresetEditor>()
    override val editors: List<PresetEditor> get() = mutableEditors

    private var emptyStatePanel: JPanel? = null
    private val branchLoads = BranchLoadCoordinator(service.scope) {
        service.gitClient.openOperation()
    }
    private val actions = PresetCollectionActions(project, service, gitRoot, log, this)
    private val resultPresenter = SwitchResultPresenter(project, service)
    private val singleRepositorySwitcher = SingleRepositorySwitcher(
        operations = GitBackgroundRunner(project, service.gitClient),
        tryAcquireWrite = service::tryAcquireWrite,
        log = log,
    )

    fun reload() = actions.reload()
    fun addPreset() = actions.addPreset()
    fun addPresetFromCurrent() = actions.addPresetFromCurrent()
    fun openConfig() = actions.openConfig()
    fun exportPresets() = actions.exportPresets()
    fun importPresets() = actions.importPresets()

    override fun clearEditors() {
        mutableEditors.forEach(PresetEditor::dispose)
        mutableEditors.clear()
        presetsInner.removeAll()
        emptyStatePanel = null
    }

    /** Disposes every editor and cancels in-flight branch discovery (Tool Window close). */
    fun dispose() {
        clearEditors()
        branchLoads.close()
    }

    /** Enables or disables every editor's switch/derive buttons while a mutation runs. */
    fun setActionsEnabled(enabled: Boolean) {
        mutableEditors.forEach { it.setActionsEnabled(enabled) }
    }

    override fun addEditor(root: Path, preset: Preset) {
        emptyStatePanel?.let {
            presetsInner.remove(it)
            emptyStatePanel = null
        }
        lateinit var editor: PresetEditor
        editor = PresetEditor(
            gitRoot = root,
            initialPreset = preset,
            log = log,
            onSwitch = onSwitch,
            onSave = { updated, onComplete -> actions.saveEditor(editor, updated, onComplete) },
            onDelete = { actions.deleteEditor(editor) },
            onDerive = { draft, branchName -> onDerive(root, draft, branchName) },
            nameValidator = { newName ->
                mutableEditors.none { it !== editor && it.currentPreset().name == newName }
            },
            branchLoads = branchLoads,
            onSwitchOnly = { path, target -> switchSubmodule(root, path, target) },
        )
        mutableEditors.add(editor)
        val wrapper = CompactHeightPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JPanel.LEFT_ALIGNMENT
            add(editor, BorderLayout.CENTER)
            add(Box.createVerticalStrut(4), BorderLayout.SOUTH)
        }
        presetsInner.add(wrapper)
    }

    override fun removeEditor(editor: PresetEditor) {
        editor.dispose()
        mutableEditors.remove(editor)
        val wrapper = editor.parent
        if (wrapper != null && wrapper !== presetsInner) {
            presetsInner.remove(wrapper)
        } else {
            presetsInner.remove(editor)
        }
        if (mutableEditors.isEmpty()) showEmptyState()
        refreshList()
    }

    override fun showEmptyState() {
        val panel = createEmptyState()
        emptyStatePanel = panel
        presetsInner.add(panel)
    }

    override fun refreshList() {
        presetsInner.revalidate()
        presetsInner.repaint()
    }

    override fun refreshParent() {
        presetsInner.parent?.revalidate()
        presetsInner.parent?.repaint()
    }

    override fun notifyStateChanged() {
        onStateChanged()
    }

    private fun switchSubmodule(root: Path, path: String, target: String) {
        val job = singleRepositorySwitcher.start(
            scope = service.scope,
            root = root,
            path = path,
            target = target,
            title = Bundle.msg("progress.switching.to", target),
        ) { outcome ->
            val operationLog = log.withContext(outcome.operationId)
            refreshVcsTail(project, root, setOf(path), operationLog, project::invokeLaterIfAlive) {
                resultPresenter.presentSingleSwitch(path, target, outcome.result, outcome.operationId, operationLog)
                notifyStateChanged()
            }
        }
        if (job == null) {
            Notifier.warn(
                project,
                Bundle.msg("notify.write.busy"),
                Bundle.msg("notify.write.busy.msg"),
            )
            return
        }
        // Claim the tool-window busy state only once this switch owns the write lease,
        // so a rejected switch never leaves the window stuck (mirrors the derive path).
        onBusyChange?.invoke(true)
        job.invokeOnCompletion { project.invokeLaterIfAlive { onBusyChange?.invoke(false) } }
    }

    private fun createEmptyState(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(32, 16, 32, 16)
            alignmentX = JPanel.CENTER_ALIGNMENT
            add(JLabel(AllIcons.Vcs.Branch).apply {
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(16))
            add(emptyStateLabel(Bundle.msg("empty.no.presets")).apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(emptyStateLabel(Bundle.msg("empty.hint")).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(20))
            val fromCurrentButton = jButton(Bundle.msg("empty.from.current"), AllIcons.Vcs.Branch) {
                addActionListener { actions.addPresetFromCurrent() }
            }
            val manualButton = jButton(Bundle.msg("empty.manual"), AllIcons.General.Add) {
                addActionListener { actions.addPreset() }
            }
            add(ResponsiveRowPanel(
                leading = fromCurrentButton,
                trailing = manualButton,
                horizontalGap = JBUI.scale(8),
                verticalGap = JBUI.scale(4),
                arrangement = ResponsiveRowArrangement.PACKED_CENTER,
            ).apply {
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(24))
            add(createQuickGuide())
        }
    }

    private fun createQuickGuide(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JPanel.CENTER_ALIGNMENT
            border = JBUI.Borders.compound(
                javax.swing.BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(12, 16, 12, 16),
            )
            add(emptyStateLabel(Bundle.msg("empty.guide.title")).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            listOf(
                "1" to Bundle.msg("empty.guide.step1"),
                "2" to Bundle.msg("empty.guide.step2"),
                "3" to Bundle.msg("empty.guide.step3"),
            ).forEach { (number, text) ->
                add(emptyStateLabel("$number. $text").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor.GRAY
                    alignmentX = JPanel.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(2))
            }
            add(Box.createVerticalStrut(4))
            add(emptyStateLabel(Bundle.msg("empty.guide.tip")).apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
        }
    }

    private fun emptyStateLabel(text: String): JLabel = ShrinkableLabel(text).apply {
        toolTipText = text
    }
}
