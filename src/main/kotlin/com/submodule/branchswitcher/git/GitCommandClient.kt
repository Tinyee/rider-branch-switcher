package com.submodule.branchswitcher.git

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

fun selectRemoteName(remotes: List<String>): String = when {
    remotes.isEmpty() -> "origin"
    "origin" in remotes -> "origin"
    else -> remotes.first()
}

/**
 * Implements Git workflow commands for one cancellation scope.
 */
internal class GitCommandClient(
    private val processRunner: GitProcessRunner,
    private val remoteCache: ConcurrentHashMap<String, String>,
) : GitOperationSession, RepositoryStateBatchGitClient, SwitchPreflightBatchGitClient {
    private val cancellation = AtomicBoolean(false)

    override fun cancel() {
        cancellation.set(true)
    }

    override fun close() {
        cancellation.set(true)
    }

    private fun run(workDir: File, vararg args: String): GitResult =
        processRunner.run(workDir, cancellation, args.asList())

    private fun run(workDir: File, args: List<String>): GitResult =
        processRunner.run(workDir, cancellation, args)

    override fun currentBranch(workDir: File): String? {
        val result = run(workDir, "symbolic-ref", "--short", "-q", "HEAD")
        return when {
            result.ok -> result.stdout.trim().ifEmpty { null }
            result.exitCode == 1 -> null
            else -> throw GitQueryException(result)
        }
    }

    override fun isGitRepo(workDir: File): Boolean {
        if (!workDir.isDirectory) return false
        val result = run(workDir, "rev-parse", "--show-toplevel")
        if (!result.ok) {
            if (File(workDir, ".git").exists() || result.failureKind != GitFailureKind.GIT_FAILED) {
                throw GitQueryException(result)
            }
            return false
        }
        if (result.stdout.isBlank()) throw GitQueryException(result)
        val topLevel = runCatching { File(result.stdout).canonicalFile }.getOrNull() ?: return false
        val requested = runCatching { workDir.canonicalFile }.getOrNull() ?: return false
        return topLevel == requested
    }

    override fun isDirty(workDir: File): Boolean {
        val result = run(workDir, "status", "--porcelain")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.isNotBlank()
    }

    override fun dirtyFileCount(workDir: File): Int {
        val result = run(workDir, "status", "--porcelain")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.lines().count { it.isNotBlank() }
    }

    override fun inspectRepositoryState(workDir: File): GitRepositoryInspection {
        if (!hasRepositoryMarker(workDir)) return missingRepositoryInspection()
        return inspectStatus(workDir)
    }

    override fun inspectPreflight(
        workDir: File,
        targetBranches: Set<String>,
    ): GitRepositoryInspection {
        if (!hasRepositoryMarker(workDir)) return missingRepositoryInspection()
        val status = inspectStatus(workDir)
        if (targetBranches.isEmpty()) return status

        val remote = remoteName(workDir)
        val result = run(
            workDir,
            listOf("for-each-ref", "--format=%(refname)") + targetBranches.flatMap { branch ->
                listOf("refs/heads/$branch", "refs/remotes/$remote/$branch")
            },
        )
        if (!result.ok) throw GitQueryException(result)
        val refs = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        return status.copy(
            localBranches = targetBranches.filterTo(linkedSetOf()) { "refs/heads/$it" in refs },
            remoteBranches = targetBranches.filterTo(linkedSetOf()) { "refs/remotes/$remote/$it" in refs },
        )
    }

    private fun inspectStatus(workDir: File): GitRepositoryInspection {
        val result = run(workDir, "status", "--porcelain=v2", "--branch", "--untracked-files=normal")
        if (!result.ok) throw GitQueryException(result)
        return parsePorcelainV2Status(result.stdout)
    }

    private fun hasRepositoryMarker(workDir: File): Boolean =
        workDir.isDirectory && File(workDir, ".git").exists()

    private fun missingRepositoryInspection(): GitRepositoryInspection =
        GitRepositoryInspection(
            isGitRepository = false,
            currentBranch = null,
            head = null,
            dirtyFileCount = -1,
        )

    override fun stash(workDir: File, message: String): GitResult =
        run(workDir, "stash", "push", "-u", "-m", message)

    override fun fetch(workDir: File): GitResult = run(workDir, "fetch", "--prune")

    override fun localBranchExists(workDir: File, branch: String): Boolean {
        val result = run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch")
        return when {
            result.ok -> true
            result.exitCode == 1 -> false
            else -> throw GitQueryException(result)
        }
    }

    override fun localBranchProbe(workDir: File, branch: String): Boolean? {
        return try {
            val result = run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch")
            when {
                result.ok -> true
                result.exitCode == 1 -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun dirtyProbe(workDir: File): Boolean? {
        return try {
            val result = run(workDir, "status", "--porcelain")
            if (result.ok) result.stdout.isNotBlank() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteName(workDir: File): String {
        val key = workDir.absolutePath
        return remoteCache[key] ?: run {
            val result = run(workDir, "remote")
            if (!result.ok) throw GitQueryException(result)
            val remotes = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val name = selectRemoteName(remotes)
            remoteCache[key] = name
            name
        }
    }

    override fun remoteBranchExists(workDir: File, branch: String): Boolean {
        val result = run(workDir, "show-ref", "--verify", "--quiet", "refs/remotes/${remoteName(workDir)}/$branch")
        return when {
            result.ok -> true
            result.exitCode == 1 -> false
            else -> throw GitQueryException(result)
        }
    }

    override fun checkoutExisting(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", branch)

    override fun resetHard(workDir: File, revision: String): GitResult =
        run(workDir, "reset", "--hard", revision)

    override fun checkoutFromRemote(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch, "${remoteName(workDir)}/$branch")

    override fun pullFf(workDir: File, branch: String): GitResult =
        run(workDir, "pull", "--ff-only", remoteName(workDir), branch)

    override fun submoduleSync(gitRoot: File): GitResult =
        run(gitRoot, "submodule", "sync", "--recursive")

    override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
        run(gitRoot, "submodule", "update", "--init", "--recursive", "--", path)

    override fun registeredSubmodulePaths(gitRoot: File): Set<String> =
        listSubmodulePaths(gitRoot).toSet()

    override fun listSubmodulePaths(gitRoot: File): List<String> {
        val result = mutableListOf<String>()
        val visited = HashSet<String>()
        val rootCanonical = try {
            gitRoot.canonicalFile.path
        } catch (_: Exception) {
            gitRoot.absolutePath
        }
        visited.add(rootCanonical)
        collectSubmodulePaths(gitRoot, "", result, visited, rootCanonical)
        return result
    }

    @Suppress("TooGenericExceptionCaught") // canonical-path failures must skip unsafe submodule entries
    private fun collectSubmodulePaths(
        baseDir: File,
        prefix: String,
        result: MutableList<String>,
        visited: MutableSet<String>,
        rootCanonical: String,
        depth: Int = 0,
    ) {
        if (depth > MAX_SUBMODULE_DEPTH) return
        val file = File(baseDir, ".gitmodules")
        if (!file.exists()) return
        val paths = file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@mapNotNull null
            val value = PATH_LINE_REGEX.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            value.trim().trim('"').takeIf { it.isNotEmpty() }
        }
        for (path in paths) {
            if (!isSafeSubmodulePath(path)) continue
            val fullPath = if (prefix.isEmpty()) path else "$prefix/$path"
            val subDir = File(baseDir, path)
            val resolved = try {
                subDir.canonicalFile.path
            } catch (e: Exception) {
                LOG.warning("Cannot resolve canonical path for submodule $fullPath: ${e.message}")
                continue
            }
            if (!resolved.startsWith(rootCanonical + File.separator)) continue
            if (!visited.add(resolved)) continue
            result.add(fullPath)
            collectSubmodulePaths(subDir, fullPath, result, visited, rootCanonical, depth + 1)
        }
    }

    override fun stashPop(workDir: File): GitResult =
        run(workDir, "stash", "pop")

    override fun checkoutNewBranch(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch)

    override fun deleteBranch(workDir: File, branch: String): GitResult =
        run(workDir, "branch", "-d", branch)

    override fun revParseHead(workDir: File): String? {
        val result = run(workDir, "rev-parse", "HEAD")
        if (!result.ok && result.failureKind != GitFailureKind.GIT_FAILED) throw GitQueryException(result)
        return if (result.ok) result.stdout.trim().ifEmpty { null } else null
    }

    override fun listAllBranches(workDir: File): List<String> {
        val remote = remoteName(workDir)
        val result = run(
            workDir,
            "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads",
            "refs/remotes/$remote",
        )
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$remote/HEAD") }
            .map { if (it.startsWith("$remote/")) it.removePrefix("$remote/") else it }
            .distinct()
            .sorted()
    }

    private fun isSafeSubmodulePath(path: String): Boolean {
        if (path.isEmpty() || path == "." || path == "..") return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        return path.split("/", "\\").none { it == ".." }
    }

    companion object {
        private const val MAX_SUBMODULE_DEPTH = 10
        private val PATH_LINE_REGEX = Regex("""^path\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
        private val LOG = Logger.getLogger(GitCommandClient::class.java.name)
    }
}
