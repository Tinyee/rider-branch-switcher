package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.ProgressCancellationHandle
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.presentation.shouldShowForceWarning
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchPreflight
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Owns switch preflight progress and confirmation dialogs. */
internal class SwitchPreflightUi(
    private val project: Project,
    private val service: BranchSwitcherService,
) {
    @Suppress("TooGenericExceptionCaught") // platform task failures are logged before crossing the UI boundary
    suspend fun probe(root: Path, preset: Preset, log: AppLogger): List<PreflightRow> {
        val git = service.gitClient
        val operationLog = log.withContext(newOperationId("preflight"))
        operationLog.activity(
            "operation started: root=${root.toAbsolutePath().normalize()}, " +
                "preset='${preset.name}', targets=${preset.targets().size}",
        )
        val rows = try {
            TaskBridge.runModal(project, Bundle.msg("progress.preflight"), true) { indicator ->
                indicator.isIndeterminate = false
                SwitchPreflight(
                    git,
                    Bundle.msg("preflight.probe.error.suffix"),
                    platformCancellationClassifier,
                ) { path, error ->
                    operationLog.warn("repository probe failed: path=$path", error)
                }
                    .probe(root, preset, ProgressCancellationHandle(indicator)) { index, total, label ->
                        indicator.text2 = label
                        indicator.fraction = index.toDouble() / total
                    }
            }
        } catch (error: Exception) {
            if (platformCancellationClassifier.isCancellation(error)) {
                operationLog.info("operation finished: status=cancelled")
            } else {
                operationLog.error("operation finished: status=failed", error)
            }
            throw error
        }
        operationLog.activity(
            "operation finished: rows=${rows.size}, probeFailures=${rows.count { it.probeError != null }}",
        )
        return rows
    }

    fun confirmForceSwitch(preset: Preset, probeResult: List<PreflightRow>): Boolean {
        val request = service.resolveSwitchRequest(preset)
        if (!shouldShowForceWarning(request, probeResult)) return true
        return confirm {
            Messages.showYesNoDialog(
                project,
                Bundle.msg("dialog.force.confirm.msg", preset.name),
                Bundle.msg("dialog.force.confirm.title"),
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
    }

    fun confirmPreflightWarnings(probeResult: List<PreflightRow>): Boolean {
        val missingDirectories = probeResult.filter { !it.exists }
        val missingBranches = probeResult.filter { it.branchMissing }
        if (missingDirectories.isEmpty() && missingBranches.isEmpty()) return true

        val warnings = buildList {
            if (missingDirectories.isNotEmpty()) {
                add(
                    Bundle.msg(
                        "preflight.warn.dir.missing",
                        missingDirectories.joinToString(", ") { it.label },
                    ),
                )
            }
            if (missingBranches.isNotEmpty()) {
                add(
                    Bundle.msg(
                        "preflight.warn.branch.not.found",
                        missingBranches.joinToString(", ") { it.label },
                    ),
                )
            }
        }
        return confirm {
            Messages.showYesNoDialog(
                project,
                warnings.joinToString("\n\n") + "\n\n" + Bundle.msg("preflight.warn.continue"),
                Bundle.msg("dialog.switch.title"),
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
    }

    fun confirmSubmoduleInitialization(path: String): Boolean = confirm {
        Messages.showYesNoDialog(
            Bundle.msg("dialog.init.submodule", path),
            Bundle.msg("dialog.init.title"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    }

    private fun confirm(dialog: () -> Boolean): Boolean {
        val confirmed = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeAndWait {
            confirmed.set(dialog())
        }
        return confirmed.get()
    }
}
