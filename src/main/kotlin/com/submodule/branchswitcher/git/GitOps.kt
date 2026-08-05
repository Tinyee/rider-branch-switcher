package com.submodule.branchswitcher.git

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private class GitOpsComponents(
    timeoutSeconds: Int,
    processStarter: (ProcessBuilder) -> Process,
) {
    val processRunner = GitProcessRunner(timeoutSeconds, processStarter = processStarter)
    val directClient = GitCommandClient(processRunner, ConcurrentHashMap())
}

/**
 * CLI-based [GitClient] facade.
 *
 * Direct calls share one cancellation scope. Each [openOperation] call receives
 * an isolated scope whose commands fail before spawning a process after cancellation.
 */
class GitOps private constructor(
    private val components: GitOpsComponents,
) : GitClient,
    GitWorkflowClient by components.directClient,
    RepositoryStateBatchGitClient,
    SwitchPreflightBatchGitClient {

    constructor(
        timeoutSeconds: Int = 60,
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ) : this(GitOpsComponents(timeoutSeconds, processStarter))

    override fun cancel() = components.directClient.cancel()

    override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
        components.directClient.inspectRepositoryState(workDir)

    override fun inspectPreflight(
        workDir: File,
        targetBranches: Set<String>,
    ): GitRepositoryInspection = components.directClient.inspectPreflight(workDir, targetBranches)

    override fun openOperation(): GitOperationSession =
        GitCommandClient(components.processRunner, ConcurrentHashMap())

    companion object {
        private val LOG = Logger.getInstance("SubmoduleBranchSwitcher")

        // --version output is one line, so asynchronous stream draining is unnecessary here.
        @Suppress("TooGenericExceptionCaught") // availability checks convert all process-start failures to diagnostics
        fun isGitOnPath(): Boolean {
            return try {
                val exitCode = ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (exitCode != 0) LOG.warn("Git availability check failed with exit code $exitCode")
                exitCode == 0
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                LOG.warn("Git availability check was interrupted", interrupted)
                false
            } catch (error: Exception) {
                LOG.warn("Git availability check failed", error)
                false
            }
        }
    }
}
