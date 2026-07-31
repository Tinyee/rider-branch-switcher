package com.submodule.branchswitcher.git

import java.io.File
import java.util.concurrent.ConcurrentHashMap

private class GitOpsComponents(
    timeoutSeconds: Int,
    processStarter: (ProcessBuilder) -> Process,
) {
    val processRunner = GitProcessRunner(timeoutSeconds, processStarter = processStarter)
    val remoteCache = ConcurrentHashMap<String, String>()
    val directClient = GitCommandClient(processRunner, remoteCache)
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
        GitCommandClient(components.processRunner, components.remoteCache)

    companion object {
        // --version output is one line, so asynchronous stream draining is unnecessary here.
        fun isGitOnPath(): Boolean = try {
            ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
