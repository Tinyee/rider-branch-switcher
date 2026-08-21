package com.submodule.branchswitcher.git.impl

import com.intellij.openapi.diagnostic.Logger as IdeaLogger
import com.submodule.branchswitcher.git.DeriveGitClient
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.GitRuntimeInfo
import com.submodule.branchswitcher.git.HeadAndBranch
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.RepositoryStateBatchGitClient
import com.submodule.branchswitcher.git.RepositoryStateBatchInspection
import com.submodule.branchswitcher.git.SubmoduleDiscoveryException
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.git.SwitchPreflightBatchGitClient
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
    /**
     * Cancellation flag. Direct calls share one flag (GitOps's cancellation scope);
     * operation sessions get an isolated flag so [cancel]/[close] affect only them.
     */
    cancellation: AtomicBoolean = AtomicBoolean(false),
) : GitOperationSession, RepositoryStateBatchGitClient, SwitchPreflightBatchGitClient, RepositoryStateBatchInspection {
    private val cancellation = cancellation

    /**
     * Cancels this session's commands (idempotent). The session's command scope is
     * this client's only resource, so [close] is deliberately identical to [cancel] —
     * both set the flag; there is no separate process ownership to release. The
     * [GitOperationSession] contract treats cancel and close as independent because
     * other implementations hold ownership beyond the cancellation flag.
     */
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
            if (!isNotGitRepoResult(workDir, result)) throw GitQueryException(result)
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
            if (!isNotGitRepoResult(workDir, result)) throw GitQueryException(result)
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

    /**
     * True when a failed `rev-parse` is the normal "not a git repository" negative rather
     * than a genuine query failure. A repository with a `.git` marker, or any failure kind
     * other than a plain git failure, must surface as an exception.
     */
    private fun isNotGitRepoResult(workDir: File, result: GitResult): Boolean =
        result.failureKind == GitFailureKind.GIT_FAILED && !File(workDir, ".git").exists()

    private fun directGitDirectory(workDir: File): File? {
        val dotGit = File(workDir, ".git")
        if (dotGit.isDirectory) return dotGit
        if (!dotGit.isFile) return null
        // A worktree's `.git` is a single `gitdir: <path>` line; anything else is not
        // resolvable, so fall through to null instead of treating the line as a path.
        val rawPath = runCatching {
            dotGit.useLines { lines ->
                lines.firstOrNull()?.trim()
                    ?.takeIf { it.startsWith("gitdir: ") }
                    ?.removePrefix("gitdir: ")?.trim()
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

    /** Same single-invocation read as [inspectRepositoryState]; exposed for capability probes. */
    override fun inspectRepositoryStateIfAvailable(workDir: File): GitRepositoryInspection? =
        inspectRepositoryState(workDir)

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

    override fun untrackedFiles(workDir: File): List<String> {
        val result = run(workDir, "--no-optional-locks", "ls-files", "--others", "--exclude-standard")
        if (!result.ok) throw GitQueryException(result)
        // Paths must not be trimmed: a file literally named " leading.txt" would lose its
        // leading space and escape collision detection before checkout silently overwrites it.
        return result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
    }

    override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        // Match BranchCheckout's selection: a local branch wins, else the remote-tracking ref.
        val ref = if (localBranchExists(workDir, branch)) branch else "${remoteName(workDir)}/$branch"
        return paths.chunked(PATHSPEC_CHUNK_SIZE).flatMap { chunk ->
            val result = run(workDir, listOf("ls-tree", "-r", "--name-only", ref, "--") + chunk)
            if (!result.ok) throw GitQueryException(result)
            result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
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
        for (entry in entries) {
            if (!isSafeSubmodulePath(entry.path)) continue
            val fullPath = if (prefix.isEmpty()) entry.path else "$prefix/${entry.path}"
            val subDir = File(baseDir, entry.path)
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
                    sectionName = entry.sectionName,
                    parentPath = prefix.ifEmpty { "." },
                    url = entry.url,
                ),
            )
            collectSubmoduleRegistrations(subDir, fullPath, result, visited, rootCanonical, depth + 1)
        }
    }

    private fun readSubmoduleEntries(baseDir: File, file: File): List<SubmoduleEntry> {
        val result = run(
            baseDir,
            "config",
            "--null",
            "--file",
            file.absolutePath,
            "--get-regexp",
            SUBMODULE_ENTRY_KEY_REGEX,
        )
        if (!result.ok) {
            if (result.exitCode == 1 && result.failureKind == GitFailureKind.GIT_FAILED) return emptyList()
            throw GitQueryException(result)
        }
        val paths = LinkedHashMap<String, String>()
        val urls = LinkedHashMap<String, String>()
        result.stdout.split('\u0000').forEach { record ->
            if (record.isEmpty()) return@forEach
            val separator = record.indexOf('\n')
            if (separator <= 0) throw GitQueryException(
                GitResult(result.cmd, 1, result.stdout, "invalid null-delimited git config output"),
            )
            val key = record.substring(0, separator)
            val value = record.substring(separator + 1)
            val bare = key.removePrefix(SUBMODULE_KEY_PREFIX)
            when {
                bare.endsWith(SUBMODULE_PATH_SUFFIX) ->
                    paths[bare.removeSuffix(SUBMODULE_PATH_SUFFIX)] = value
                bare.endsWith(SUBMODULE_URL_SUFFIX) ->
                    urls[bare.removeSuffix(SUBMODULE_URL_SUFFIX)] = value
            }
        }
        return paths.map { (sectionName, path) ->
            SubmoduleEntry(sectionName, path, urls[sectionName])
        }
    }

    private data class SubmoduleEntry(
        val sectionName: String,
        val path: String,
        val url: String?,
    )

    /**
     * Reads a git revision that may legitimately not exist (an empty stash ref, or HEAD
     * in a freshly initialized repository). A plain git failure is the normal negative
     * and maps to null; any other failure kind is a genuine query failure.
     */
    private fun revParseOptional(workDir: File, vararg args: String): String? {
        val result = run(workDir, "rev-parse", *args)
        if (!result.ok && result.failureKind != GitFailureKind.GIT_FAILED) throw GitQueryException(result)
        return if (result.ok) result.stdout.trim().ifEmpty { null } else null
    }

    override fun stashTopOid(workDir: File): String? = revParseOptional(workDir, "--verify", "refs/stash")

    override fun stashOidByMessage(workDir: File, messagePrefix: String): String? {
        // %gs is the stash reflog subject ("On <branch>: <message>"), so matching is
        // scoped to stashes this plugin created rather than whatever sits on top of
        // refs/stash (a concurrent external `git stash push` must not be misapplied).
        // `git stash list` exits 0 on an empty stash, so a non-zero exit is a genuine
        // query failure, never a normal "no match". Failing open here (returning null)
        // would defeat the message-prefix race guard: a transient failure would fall
        // back to the stack top and could apply an external stash. This differs from
        // revParseOptional's fail-open, where `--verify refs/stash` exit-1 is the
        // normal negative.
        val result = run(workDir, "stash", "list", "--format=%H%x09%gs")
        if (!result.ok) throw GitQueryException(result)
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .firstOrNull { (_, subject) -> subject.contains(messagePrefix) }
            ?.first
    }

    override fun stashApply(workDir: File, oid: String): GitResult =
        run(workDir, "stash", "apply", oid)

    override fun stashDrop(workDir: File, oid: String): GitResult =
        run(workDir, "stash", "drop", oid)

    override fun checkoutNewBranch(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch)

    override fun deleteBranch(workDir: File, branch: String): GitResult =
        run(workDir, "branch", "-d", branch)

    override fun revParseHead(workDir: File): String? = revParseOptional(workDir, "HEAD")

    override fun headAndBranch(workDir: File): HeadAndBranch? {
        // `git status --porcelain=v2 --branch --untracked-files=no` reports both the
        // HEAD SHA and the current branch in one invocation, so the two facts cannot
        // drift apart. --untracked-files=no keeps the output bounded even for
        // pathological untracked trees (only branch header + tracked changes).
        val result = run(
            workDir,
            "--no-optional-locks",
            "status",
            "--porcelain=v2",
            "--branch",
            "--untracked-files=no",
        )
        if (!result.ok) throw GitQueryException(result)
        val inspection = parsePorcelainV2Status(result.stdout)
        return HeadAndBranch(inspection.head, inspection.currentBranch)
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
        /** Bounds `ls-tree` pathspec lists so exec arg size stays well under ARG_MAX. */
        private const val PATHSPEC_CHUNK_SIZE = 400
        private const val MAX_SUBMODULE_DEPTH = 10
        private const val SUBMODULE_ENTRY_KEY_REGEX = "^submodule\\..*\\.(path|url)$"
        private const val SUBMODULE_KEY_PREFIX = "submodule."
        private const val SUBMODULE_PATH_SUFFIX = ".path"
        private const val SUBMODULE_URL_SUFFIX = ".url"
        private val LOG = IdeaLogger.getInstance("SubmoduleBranchSwitcher")
    }
}
