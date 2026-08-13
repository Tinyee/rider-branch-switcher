package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.OperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.ProgressCancellationHandle
import com.submodule.branchswitcher.platform.platformCancellationClassifier
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
    suspend fun probe(
        root: Path,
        preset: Preset,
        log: AppLogger,
        operationContext: OperationContext,
    ): List<PreflightRow> {
        // Isolated cancellation scope: the direct client shares one global flag and
        // is never cancelled, so an in-flight `git status` would keep running until its
        // timeout. A dedicated session lets the modal cancel signal reach it promptly.
        val operation = service.gitClient.openOperation()
        val operationLog = log.withContext(operationContext.inPhase("preflight"))
        operationLog.activity(
            "operation started: root=${root.toAbsolutePath().normalize()}, " +
                "preset='${preset.name}', targets=${preset.targets().size}",
        )
        val rows = try {
            TaskBridge.runModal(project, Bundle.msg("progress.preflight"), true) { indicator ->
                indicator.isIndeterminate = false
                val cancelWatcher = ModalCancelWatcher(indicator) { operation.cancel() }
                try {
                    SwitchPreflight(
                        operation,
                        Bundle.msg("preflight.probe.error.suffix"),
                        platformCancellationClassifier,
                    ) { path, error ->
                        operationLog.warn("repository probe failed: path=$path", error)
                    }
                        .probe(root, preset, ProgressCancellationHandle(indicator)) { index, total, label ->
                            indicator.text2 = label
                            indicator.fraction = index.toDouble() / total
                        }
                } finally {
                    cancelWatcher.stop()
                }
            }
        } catch (error: Exception) {
            if (platformCancellationClassifier.isCancellation(error)) {
                operationLog.info("operation finished: status=cancelled")
            } else {
                // Recorded here with context, then rethrown for the single reporting
                // boundary (SwitchController / SwitchPresetAction). Using failure keeps
                // this from duplicating their fatal-error report.
                operationLog.failure("operation finished: status=failed", error)
            }
            throw error
        } finally {
            operation.close()
        }
        operationLog.activity(
            "operation finished: rows=${rows.size}, probeFailures=${rows.count { it.probeError != null }}",
        )
        return rows
    }

    /**
     * Watches a modal task indicator and cancels the Git session the moment the user
     * cancels. The modal's own [ProgressIndicator.checkCanceled] only fires between
     * probe targets; without this watcher an in-flight `git status` would not receive
     * the session cancel and would run until its timeout. The Git process runner polls
     * the session's cancellation flag every 100ms, so this stops the command promptly.
     */
    private class ModalCancelWatcher(
        private val indicator: ProgressIndicator,
        private val onCancel: () -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)
        private val thread = Thread {
            try {
                while (!stopped.get() && !indicator.isCanceled) {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // stop() interrupts the sleep to end the watch promptly.
            }
            if (!stopped.get()) onCancel()
        }.apply {
            isDaemon = true
            name = "branch-switcher-preflight-cancel-watcher"
            start()
        }

        fun stop() {
            stopped.set(true)
            thread.interrupt()
        }

        private companion object {
            const val POLL_INTERVAL_MS = 100L
        }
    }

    /**
     * One upfront confirmation for every missing submodule directory before the
     * switch starts. Called from the UI/launcher coroutine, never from the
     * ProgressManager write worker, so the background switch never blocks on a dialog.
     */
    fun confirmSubmoduleInitializations(missingPaths: List<String>): Boolean = confirm {
        Messages.showYesNoDialog(
            Bundle.msg("dialog.init.submodules", missingPaths.joinToString(", ")),
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
