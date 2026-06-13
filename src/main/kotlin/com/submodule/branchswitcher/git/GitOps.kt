package com.submodule.branchswitcher.git

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun selectRemoteName(remotes: List<String>): String = when {
    remotes.isEmpty() -> "origin"
    "origin" in remotes -> "origin"
    else -> remotes.first()
}

fun safeTimeoutMillis(timeoutSeconds: Int): Int = timeoutSeconds.coerceIn(1, 3600) * 1000

/**
 * CLI-based [GitClient] implementation using [ProcessBuilder] with cancellable polling.
 * All git commands inherit [timeoutSeconds] (default 60s).
 *
 * Cancellation remains active from [cancel] until [endOperation]. This ensures
 * commands reached after cancellation fail before spawning another process.
 */
class GitOps(
    private val timeoutSeconds: Int = 60,
    private val processStarter: (ProcessBuilder) -> Process = { it.start() },
) : GitClient {

    private val operationCancelled = AtomicBoolean(false)
    private val activeOperations = AtomicInteger(0)

    override fun beginOperation() {
        if (activeOperations.incrementAndGet() == 1) {
            operationCancelled.set(false)
        }
    }

    override fun cancel() = operationCancelled.set(true)

    override fun endOperation() {
        val remaining = activeOperations.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            operationCancelled.set(false)
        }
    }

    /** Executes `git [args]` in [workDir], polling for cancellation and timeout every 100ms. */
    private fun run(workDir: File, vararg args: String): GitResult {
        val cmdLabel = "git ${args.joinToString(" ")}"
        if (operationCancelled.get()) {
            return GitResult(cmdLabel, -1, "", "cancelled")
        }
        val pb = ProcessBuilder("git", *args)
            .directory(workDir)
            .redirectErrorStream(false)
        val process: Process = try {
            processStarter(pb)
        } catch (e: Exception) {
            return GitResult(cmdLabel, -1, "", "failed to start: ${e.message}")
        }

        // Read stdout/stderr on background threads so the pipe buffers don't block the process
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        val deadline = System.currentTimeMillis() + safeTimeoutMillis(timeoutSeconds)
        var exitCode: Int
        while (true) {
            val finished = try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (finished) {
                exitCode = process.exitValue()
                break
            }
            if (operationCancelled.get()) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return GitResult(cmdLabel, -1, "", "cancelled")
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return GitResult(cmdLabel, -1, "", "timeout after ${timeoutSeconds}s")
            }
        }

        val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")

        return GitResult(cmdLabel, exitCode, stdout.trim(), stderr.trim())
    }

    override fun currentBranch(workDir: File): String? {
        val r = run(workDir, "symbolic-ref", "--short", "-q", "HEAD")
        return if (r.ok) r.stdout.trim().ifEmpty { null } else null
    }

    override fun isDirty(workDir: File): Boolean {
        val r = run(workDir, "status", "--porcelain")
        return r.ok && r.stdout.isNotBlank()
    }

    override fun dirtyFileCount(workDir: File): Int {
        val r = run(workDir, "status", "--porcelain")
        if (!r.ok) return -1
        return r.stdout.lines().count { it.isNotBlank() }
    }

    /** Stashes all changes including untracked files (-u). */
    override fun stash(workDir: File, message: String): GitResult =
        run(workDir, "stash", "push", "-u", "-m", message)

    /** Fetches and prunes stale remote-tracking refs. */
    override fun fetch(workDir: File): GitResult = run(workDir, "fetch", "--prune")

    /** Uses plumbing command `show-ref --verify` for fast existence check. */
    override fun localBranchExists(workDir: File, branch: String): Boolean =
        run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch").ok

    /** Cache of remote name per workDir to avoid repeated `git remote` calls. */
    private val remoteCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Detect the default remote name: prefer "origin", then the first remote, falling back to "origin". */
    private fun remoteName(workDir: File): String {
        val key = workDir.absolutePath
        return remoteCache[key] ?: run {
            val r = run(workDir, "remote")
            val remotes = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val name = selectRemoteName(remotes)
            remoteCache[key] = name
            name
        }
    }

    /** Uses plumbing command `show-ref --verify` for fast existence check. */
    override fun remoteBranchExists(workDir: File, branch: String): Boolean =
        run(workDir, "show-ref", "--verify", "--quiet", "refs/remotes/${remoteName(workDir)}/$branch").ok

    override fun checkoutExisting(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", branch)

    override fun checkoutFromRemote(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch, "${remoteName(workDir)}/$branch")

    override fun pullFf(workDir: File, branch: String): GitResult =
        run(workDir, "pull", "--ff-only", remoteName(workDir), branch)

    override fun submoduleSync(gitRoot: File): GitResult =
        run(gitRoot, "submodule", "sync", "--recursive")

    override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
        run(gitRoot, "submodule", "update", "--init", "--", path)

    /** Matches `path = <value>` lines in .gitmodules, skipping comments and blank lines. */
    private val pathLineRegex = Regex("""^path\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    /** Parses .gitmodules to list submodule paths. Skips lines starting with # or ;. */
    override fun listSubmodulePaths(gitRoot: File): List<String> {
        val file = File(gitRoot, ".gitmodules")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@mapNotNull null
            val v = pathLineRegex.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            v.trim().trim('"').takeIf { it.isNotEmpty() }
        }
    }

    override fun stashPop(workDir: File): GitResult =
        run(workDir, "stash", "pop")

    override fun checkoutNewBranch(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch)

    override fun revParseHead(workDir: File): String? {
        val r = run(workDir, "rev-parse", "HEAD")
        return if (r.ok) r.stdout.trim().ifEmpty { null } else null
    }

    /**
     * Lists all branches (local + remote) from `git for-each-ref`,
     * strips `origin/` prefix, filters `origin/HEAD`, dedupes, and sorts.
     */
    override fun listAllBranches(workDir: File): List<String> {
        val remote = remoteName(workDir)
        val r = run(workDir, "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads", "refs/remotes/$remote")
        if (!r.ok) return emptyList()
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$remote/HEAD") }
            .map { if (it.startsWith("$remote/")) it.removePrefix("$remote/") else it }
            .distinct()
            .sorted()
    }
}
