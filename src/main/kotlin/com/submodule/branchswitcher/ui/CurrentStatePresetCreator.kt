package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path

internal enum class CurrentStatePresetBlockReason {
    MAIN_BRANCH_UNAVAILABLE,
    INCOMPLETE_REPOSITORIES,
}

internal fun currentStatePresetBlockReason(
    mainBranch: String?,
    skippedRepositories: List<String>,
): CurrentStatePresetBlockReason? = when {
    mainBranch.isNullOrEmpty() -> CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE
    skippedRepositories.isNotEmpty() -> CurrentStatePresetBlockReason.INCOMPLETE_REPOSITORIES
    else -> null
}

/** Creates a complete preset by probing the branches currently checked out in every repository. */
internal class CurrentStatePresetCreator(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val host: PresetCollectionHost,
    private val persist: (List<Preset>, (Boolean) -> Unit) -> Unit,
    private val nameValidator: () -> InputValidator,
) {
    fun create() {
        val root = gitRoot() ?: run {
            log.error("git root not found")
            Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
            return
        }
        service.scope.launch(Dispatchers.Default) {
            val currentState = probeCurrentState(root) ?: return@launch
            project.invokeLaterIfAlive {
                createPresetFromProbe(root, currentState)
            }
        }
    }

    private fun createPresetFromProbe(root: Path, currentState: CurrentStateProbeResult) {
        when (currentStatePresetBlockReason(currentState.mainBranch, currentState.skipped)) {
            CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE -> {
                Messages.showWarningDialog(
                    project,
                    Bundle.msg("dialog.detached.head"),
                    Bundle.msg("plugin.title"),
                )
                return
            }
            CurrentStatePresetBlockReason.INCOMPLETE_REPOSITORIES -> {
                val details = currentState.skipped.joinToString("\n") { "- $it" }
                log.warn("[from current] incomplete preset blocked: ${currentState.skipped.joinToString(", ")}")
                Messages.showWarningDialog(
                    project,
                    Bundle.msg("dialog.from.current.incomplete", details),
                    Bundle.msg("dialog.from.current"),
                )
                return
            }
            null -> Unit
        }
        val mainBranch = currentState.mainBranch ?: return

        val name = Messages.showInputDialog(
            project,
            Bundle.msg("dialog.preset.name.rule"),
            Bundle.msg("dialog.from.current"),
            null,
            mainBranch,
            nameValidator(),
        )?.trim()
        if (name.isNullOrEmpty()) return

        val newPreset = Preset(
            name = name,
            main = mainBranch,
            submodules = currentState.submodules,
        )
        persist(host.editors.map { it.currentPreset() } + newPreset) persisted@{ saved ->
            if (!saved) return@persisted
            host.addEditor(root, newPreset)
            host.refreshParent()
            log.debug(
                "[added from current] $name -> main=$mainBranch, " +
                    "${currentState.submodules.size} submodule(s)",
            )
            host.notifyStateChanged()
        }
    }

    @Suppress("TooGenericExceptionCaught") // modal and Git adapters report non-cancellation failures uniformly
    private suspend fun probeCurrentState(root: Path): CurrentStateProbeResult? {
        return try {
            TaskBridge.runModal(
                project,
                Bundle.msg("progress.read.current"),
                false,
            ) { indicator ->
                indicator.isIndeterminate = true
                indicator.text = Bundle.msg("progress.main.repo")
                val rootFile = root.toFile()
                val mainBranch = service.gitClient.currentBranch(rootFile)
                val submodules = LinkedHashMap<String, String>()
                val skipped = mutableListOf<String>()
                service.gitClient.listSubmodulePaths(rootFile).forEach { path ->
                    indicator.text = path
                    val repositoryDirectory = root.resolve(path).toFile()
                    if (!repositoryDirectory.exists() ||
                        (!repositoryDirectory.resolve(".git").exists() &&
                            !File(repositoryDirectory, ".git").isFile)
                    ) {
                        skipped += "$path (${Bundle.msg("status.tooltip.not.init")})"
                        return@forEach
                    }
                    val branch = service.gitClient.currentBranch(repositoryDirectory)
                    if (branch.isNullOrEmpty()) {
                        skipped += "$path (detached)"
                        return@forEach
                    }
                    submodules[path] = branch
                }
                CurrentStateProbeResult(mainBranch, submodules, skipped)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            null
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            null
        } catch (e: Exception) {
            log.logFailure("probe current state failed", e)
            Notifier.warn(project, Bundle.msg("plugin.title"), "${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private data class CurrentStateProbeResult(
        val mainBranch: String?,
        val submodules: LinkedHashMap<String, String>,
        val skipped: List<String>,
    )
}
