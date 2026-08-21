package com.submodule.branchswitcher.git.impl

import com.intellij.openapi.diagnostic.Logger
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.git.RepositoryStateBatchInspection
import com.submodule.branchswitcher.git.SwitchPreflightBatchGitClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    SwitchPreflightBatchGitClient,
    RepositoryStateBatchInspection {

    constructor(
        timeoutSeconds: Int = 60,
        processStarter: (ProcessBuilder) -> Process = { it.start() },
    ) : this(GitOpsComponents(timeoutSeconds, processStarter))

    override fun cancel() = components.directClient.cancel()

    override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
        components.directClient.inspectRepositoryState(workDir)

    override fun inspectRepositoryStateIfAvailable(workDir: File): GitRepositoryInspection? =
        components.directClient.inspectRepositoryStateIfAvailable(workDir)

    override fun inspectPreflight(
        workDir: File,
        targetBranches: Set<String>,
    ): GitRepositoryInspection =
        GitCommandClient(components.processRunner, ConcurrentHashMap()).use { operation ->
            operation.inspectPreflight(workDir, targetBranches)
        }

    override fun openOperation(): GitOperationSession =
        GitCommandClient(components.processRunner, ConcurrentHashMap())

    companion object {
        private val LOG = Logger.getInstance("SubmoduleBranchSwitcher")

        /** Bounds the `git --version` availability probe so a stuck PATH entry cannot hang the caller. */
        private const val AVAILABILITY_CHECK_TIMEOUT_SECONDS = 10L

        // --version output is one line, so asynchronous stream draining is unnecessary here.
        @Suppress("TooGenericExceptionCaught") // availability checks convert all process-start failures to diagnostics
        fun isGitOnPath(): Boolean {
            return try {
                val process = ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(AVAILABILITY_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    LOG.warn(
                        "Git availability check timed out after ${AVAILABILITY_CHECK_TIMEOUT_SECONDS}s",
                    )
                    process.destroyForcibly()
                    return false
                }
                val exitCode = process.exitValue()
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
