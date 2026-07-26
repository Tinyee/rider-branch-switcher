package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.GitBackgroundResult
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.refreshVcsRepos
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.resolveGitDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
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
) : PresetCollectionHost {
    private val mutableEditors = mutableListOf<PresetEditor>()
    override val editors: List<PresetEditor> get() = mutableEditors

    private var emptyStatePanel: JPanel? = null
    var onStateChanged: (() -> Unit)? = null

    private val actions = PresetCollectionActions(project, service, gitRoot, log, this)

    fun reload() = actions.reload()
    fun addPreset() = actions.addPreset()
    fun addPresetFromCurrent() = actions.addPresetFromCurrent()
    fun openConfig() = actions.openConfig()
    fun exportPresets() = actions.exportPresets()
    fun importPresets() = actions.importPresets()

    override fun clearEditors() {
        mutableEditors.clear()
        presetsInner.removeAll()
        emptyStatePanel = null
    }

    override fun addEditor(root: Path, preset: Preset) {
        emptyStatePanel?.let {
            presetsInner.remove(it)
            emptyStatePanel = null
        }
        lateinit var editor: PresetEditor
        editor = PresetEditor(
            gitRoot = root,
            initial = preset,
            log = log,
            onSwitch = onSwitch,
            onSave = { updated -> actions.saveEditor(editor, updated) },
            onDelete = { actions.deleteEditor(editor) },
            onDerive = { draft, branchName -> onDerive(root, draft, branchName) },
            nameValidator = { newName ->
                mutableEditors.none { it !== editor && it.currentPreset().name == newName }
            },
            gitClient = service.gitClient,
            scope = service.scope,
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
        onStateChanged?.invoke()
    }

    private fun switchSubmodule(root: Path, path: String, target: String) {
        val writeLease = service.tryAcquireWrite()
        if (writeLease == null) {
            Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            return
        }
        service.scope.launch(Dispatchers.Default) {
            val dir = resolveGitDir(root, path)
            var result: GitResult? = null
            var skipped: String? = null
            try {
                when (val background = GitBackgroundRunner(project, service.gitClient).run(
                    Bundle.msg("progress.switching.to", target),
                ) { indicator, operation ->
                    indicator.isIndeterminate = true
                    when {
                        !dir.exists() || !operation.isGitRepo(dir) ->
                            skipped = "repository is not initialized"
                        operation.isDirty(dir) -> skipped = "working tree dirty"
                        operation.currentBranch(dir) == target -> skipped = "already on $target"
                        operation.localBranchExists(dir, target) ->
                            result = operation.checkoutExisting(dir, target)
                        operation.remoteBranchExists(dir, target) ->
                            result = operation.checkoutFromRemote(dir, target)
                        else -> result = GitResult("checkout", 1, "", "branch $target not found")
                    }
                }) {
                    is GitBackgroundResult.Completed -> Unit
                    is GitBackgroundResult.Cancelled -> {
                        skipped = "cancelled"
                        log.info("[switch] $path: cancelled")
                    }
                    is GitBackgroundResult.Failed -> {
                        val error = background.error
                        skipped = "${error.javaClass.simpleName}: ${error.message}"
                        log.error("[switch] $path: $skipped")
                    }
                }
            } finally {
                writeLease.close()
            }
            project.invokeLaterIfAlive {
                when {
                    result?.ok == true -> {
                        log.debug("[switch] $path -> $target ok")
                        Notifier.info(
                            project,
                            Bundle.msg("switch.complete"),
                            Bundle.msg("notify.switch.only.complete", path, target),
                        )
                    }
                    result != null -> {
                        log.warn("[switch] $path failed: ${result.diagnostic()}")
                        Notifier.warn(
                            project,
                            Bundle.msg("switch.failed"),
                            Bundle.msg("notify.switch.only.failed", path, target),
                        )
                    }
                    else -> log.warn("[switch] $path skipped: $skipped")
                }
                refreshVcsRepos(project, root, setOf(path))
                notifyStateChanged()
            }
        }
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
            add(JLabel(Bundle.msg("empty.no.presets")).apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JLabel(Bundle.msg("empty.hint")).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(20))
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
                alignmentX = JPanel.CENTER_ALIGNMENT
                add(jButton(Bundle.msg("empty.from.current"), AllIcons.Vcs.Branch) {
                    addActionListener { actions.addPresetFromCurrent() }
                })
                add(jButton(Bundle.msg("empty.manual"), AllIcons.General.Add) {
                    addActionListener { actions.addPreset() }
                })
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
            add(JLabel(Bundle.msg("empty.guide.title")).apply {
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
                add(JLabel("$number. $text").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor.GRAY
                    alignmentX = JPanel.CENTER_ALIGNMENT
                })
                add(Box.createVerticalStrut(2))
            }
            add(Box.createVerticalStrut(4))
            add(JLabel(Bundle.msg("empty.guide.tip")).apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            })
        }
    }
}
