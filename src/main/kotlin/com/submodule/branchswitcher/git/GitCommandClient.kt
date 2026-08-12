package com.submodule.branchswitcher.git

import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import com.submodule.branchswitcher.switch.resolvedIdentity
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    override fun repositoryIdentity(workDir: File): RepositoryIdentity? {
        if (!workDir.isDirectory) return null
        val result = run(workDir, "rev-parse", "--absolute-git-dir", "--show-superproject-working-tree")
        if (!result.ok) {
            if (File(workDir, ".git").exists() || result.failureKind != GitFailureKind.GIT_FAILED) {
                throw GitQueryException(result)
            }
            return null
        }
        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val gitDirectory = lines.firstOrNull()?.let { path ->
            runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        } ?: throw GitQueryException(result)
        val superprojectRoot = lines.getOrNull(1)?.let { path ->
            runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        }
        return RepositoryIdentity(gitDirectory, superprojectRoot)
    }

    override fun runtimeInfo(workDir: File): GitRuntimeInfo {
        val result = run(workDir, "--version")
        if (!result.ok) throw GitQueryException(result)
        return GitRuntimeInfo(
            version = result.stdout.trim().ifEmpty { "unknown" },
            timeoutSeconds = processRunner.effectiveTimeoutSeconds,
        )
    }

    override fun isDirty(workDir: File): Boolean {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.isNotBlank()
    }

    override fun dirtyFileCount(workDir: File): Int {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.lines().count { it.isNotBlank() }
    }

    override fun isSubmoduleOnlyDirty(workDir: File): Boolean {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain=v2", "--untracked-files=normal")
        if (!result.ok) throw GitQueryException(result)
        return isSubmoduleOnlyPorcelainStatus(result.stdout)
    }

    override fun indexLockFile(workDir: File): String? {
        directGitDirectory(workDir)?.let { gitDirectory ->
            val lock = File(gitDirectory, "index.lock")
            return runCatching { lock.canonicalPath }.getOrElse { lock.path }
                .takeIf { lock.exists() }
        }
        val result = run(workDir, "rev-parse", "--git-path", "index.lock")
        if (!result.ok) {
            if (result.failureKind != GitFailureKind.GIT_FAILED) throw GitQueryException(result)
            return null
        }
        val path = result.stdout.trim()
        if (path.isEmpty()) return null
        val lock = runCatching { File(workDir, path).canonicalFile }.getOrNull()
            ?: File(workDir, path)
        return lock.path.takeIf { it.isNotEmpty() && lock.exists() }
    }

    private fun directGitDirectory(workDir: File): File? {
        val dotGit = File(workDir, ".git")
        if (dotGit.isDirectory) return dotGit
        if (!dotGit.isFile) return null
        val rawPath = runCatching {
            dotGit.useLines { lines ->
                lines.firstOrNull()?.trim()?.removePrefix("gitdir:")?.trim()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val path = File(rawPath)
            (if (path.isAbsolute) path else File(workDir, rawPath)).canonicalFile
        }.getOrNull()
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
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain=v2", "--branch", "--untracked-files=normal")
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

    override fun localBranchProbe(workDir: File, branch: String): Boolean {
        val result = run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch")
        return when {
            result.ok -> true
            result.exitCode == 1 -> false
            else -> throw GitQueryException(result)
        }
    }

    override fun dirtyProbe(workDir: File): Boolean {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.isNotBlank()
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

    override fun remoteUrl(workDir: File): String? {
        val remotesResult = run(workDir, "remote")
        if (!remotesResult.ok) throw GitQueryException(remotesResult)
        val remotes = remotesResult.stdout.lines().map(String::trim).filter(String::isNotEmpty)
        if (remotes.isEmpty()) return null
        val remote = selectRemoteName(remotes)
        remoteCache[workDir.absolutePath] = remote
        val urlResult = run(workDir, "remote", "get-url", remote)
        if (!urlResult.ok) throw GitQueryException(urlResult)
        return urlResult.stdout.trim().ifEmpty { null }
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

    override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> =
        listSubmoduleRegistrations(gitRoot)

    override fun listSubmodulePaths(gitRoot: File): List<String> {
        return listSubmoduleRegistrations(gitRoot).map(SubmoduleRegistration::path)
    }

    private fun listSubmoduleRegistrations(gitRoot: File): List<SubmoduleRegistration> {
        val result = mutableListOf<SubmoduleRegistration>()
        val visited = HashSet<String>()
        // Strict resolution: an unresolvable project root is a structured discovery
        // failure (never "no submodules"), so the caller can surface the real cause
        // instead of misreporting every target as unregistered. Only path-resolution
        // failures are wrapped; an unexpected runtime error propagates as-is so it is
        // not silently downgraded.
        val rootCanonical = try {
            gitRoot.resolvedIdentity()
        } catch (e: IOException) {
            throw SubmoduleDiscoveryException(
                "cannot resolve project root ${gitRoot.path}; submodule discovery failed",
                e,
            )
        } catch (e: SecurityException) {
            throw SubmoduleDiscoveryException(
                "cannot resolve project root ${gitRoot.path}; submodule discovery failed",
                e,
            )
        }
        visited.add(rootCanonical)
        collectSubmoduleRegistrations(gitRoot, "", result, visited, rootCanonical)
        return result
    }

    private fun collectSubmoduleRegistrations(
        baseDir: File,
        prefix: String,
        result: MutableList<SubmoduleRegistration>,
        visited: MutableSet<String>,
        rootCanonical: String,
        depth: Int = 0,
    ) {
        if (depth > MAX_SUBMODULE_DEPTH) return
        val file = File(baseDir, ".gitmodules")
        if (!file.exists()) return
        val entries = readSubmoduleEntries(baseDir, file)
        for ((sectionName, path) in entries) {
            if (!isSafeSubmodulePath(path)) continue
            val fullPath = if (prefix.isEmpty()) path else "$prefix/$path"
            val subDir = File(baseDir, path)
            val resolved = try {
                subDir.resolvedIdentity()
            } catch (e: IOException) {
                LOG.warn("Cannot resolve canonical path for submodule $fullPath", e)
                continue
            } catch (e: SecurityException) {
                LOG.warn("Cannot resolve canonical path for submodule $fullPath", e)
                continue
            }
            if (!resolved.startsWith(rootCanonical + File.separator)) continue
            if (!visited.add(resolved)) continue
            result.add(
                SubmoduleRegistration(
                    path = fullPath,
                    sectionName = sectionName,
                    parentPath = prefix.ifEmpty { "." },
                ),
            )
            collectSubmoduleRegistrations(subDir, fullPath, result, visited, rootCanonical, depth + 1)
        }
    }

    private fun readSubmoduleEntries(baseDir: File, file: File): List<Pair<String, String>> {
        val result = run(
            baseDir,
            "config",
            "--null",
            "--file",
            file.absolutePath,
            "--get-regexp",
            SUBMODULE_PATH_KEY_REGEX,
        )
        if (!result.ok) {
            if (result.exitCode == 1 && result.failureKind == GitFailureKind.GIT_FAILED) return emptyList()
            throw GitQueryException(result)
        }
        return result.stdout.split('\u0000').mapNotNull { record ->
            if (record.isEmpty()) return@mapNotNull null
            val separator = record.indexOf('\n')
            if (separator <= 0) throw GitQueryException(
                GitResult(result.cmd, 1, result.stdout, "invalid null-delimited git config output"),
            )
            val key = record.substring(0, separator)
            val path = record.substring(separator + 1)
            val sectionName = key.removePrefix(SUBMODULE_KEY_PREFIX).removeSuffix(SUBMODULE_PATH_SUFFIX)
            sectionName to path
        }
    }

    override fun stashTopOid(workDir: File): String? {
        val result = run(workDir, "rev-parse", "--verify", "refs/stash")
        if (!result.ok && result.failureKind != GitFailureKind.GIT_FAILED) throw GitQueryException(result)
        return if (result.ok) result.stdout.trim().ifEmpty { null } else null
    }

    override fun stashApply(workDir: File, oid: String): GitResult =
        run(workDir, "stash", "apply", oid)

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
        private const val SUBMODULE_PATH_KEY_REGEX = "^submodule\\..*\\.path$"
        private const val SUBMODULE_KEY_PREFIX = "submodule."
        private const val SUBMODULE_PATH_SUFFIX = ".path"
        private val LOG = IdeaLogger.getInstance("SubmoduleBranchSwitcher")
    }
}
