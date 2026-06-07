package com.submodule.branchswitcher.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BranchSwitcherToolWindowFactory : ToolWindowFactory {

    /** Checked once per project session — avoids repeated git PATH checks. */
    private val checkedProjects = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<BranchSwitcherService>()
        val panel = BranchSwitcherPanel(project, service)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Check git availability once per project
        if (checkedProjects.add(project.locationHash)) {
            checkGitAvailable(project, service)
        }
    }

    private fun checkGitAvailable(project: Project, service: BranchSwitcherService) {
        service.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val gitFound = try {
                val proc = ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            if (!gitFound) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    Notifier.warn(
                        project,
                        Bundle.msg("plugin.title"),
                        "git not found in PATH. The plugin requires git to be installed and available on the system PATH.",
                    )
                }
            }
        }
    }
}
