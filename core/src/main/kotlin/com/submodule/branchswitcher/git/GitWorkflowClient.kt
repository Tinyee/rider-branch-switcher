package com.submodule.branchswitcher.git

/**
 * All workflow capabilities exposed by a concrete Git implementation or operation session.
 */
interface GitWorkflowClient :
    SwitchGitClient,
    DeriveGitClient,
    PresetDiscoveryGitClient,
    SwitchPreflightGitClient

/**
 * Isolated cancellable Git view used by one background operation.
 *
 * Closing a session releases its process ownership; callers must not reuse it.
 */
interface GitOperationSession : GitWorkflowClient, AutoCloseable

/** Factory boundary that gives each background write its own operation session. */
interface GitOperationProvider {
    fun openOperation(): GitOperationSession
}

private class DelegatingGitOperationSession(
    private val client: GitClient,
) : GitOperationSession, GitWorkflowClient by client {

    override fun cancel() = client.cancel()

    override fun close() = Unit
}

/**
 * Aggregate implementation boundary. Consumers should depend on a workflow interface above.
 *
 * Implementations: [com.submodule.branchswitcher.git.impl.GitOps] (CLI via ProcessBuilder).
 *
 * Key semantics:
 * - [currentBranch] returns null on detached HEAD
 * - [checkoutExisting] switches to an existing local branch
 * - [checkoutFromRemote] creates a local branch tracking origin/<branch>
 * - [checkoutNewBranch] creates a new branch from current HEAD
 * - [fetch] always passes --prune
 * - [submoduleSync] uses --recursive
 * - [stash] pushes with -u (includes untracked files)
 */
interface GitClient :
    GitWorkflowClient,
    GitOperationProvider {
    /**
     * Compatibility adapter for test doubles and alternate implementations.
     * [com.submodule.branchswitcher.git.impl.GitOps] overrides this with a cancellation-isolated session.
     */
    override fun openOperation(): GitOperationSession {
        return DelegatingGitOperationSession(this)
    }
}
