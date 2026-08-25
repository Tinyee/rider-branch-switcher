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
import com.submodule.branchswitcher.git.IndexLockBlockedException
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleDiscoveryException
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.switch.OperationCancelledException
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
) : GitOperationSession {
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

    /**
     * Runs a command that mutates the index/worktree (Spec 2 index gate), re-checking for a
     * stale `index.lock` immediately before the write so a lock appearing since the last
     * check surfaces as [IndexLockBlockedException] instead of a mystery git failure. Reads
     * and ref-only writes go through [run] and stay ungated.
     */
    private fun runIndexMutation(workDir: File, vararg args: String): GitResult {
        val lock = indexLockFile(workDir)
        if (lock != null) throw IndexLockBlockedException(workDir, lock)
        return run(workDir, *args)
    }

    private fun runIndexMutation(workDir: File, args: List<String>): GitResult {
        val lock = indexLockFile(workDir)
        if (lock != null) throw IndexLockBlockedException(workDir, lock)
        return run(workDir, args)
    }

    /**
     * Converts a failed read into the exception to throw. A cancelled/interrupted git read is
     * a user cancel and surfaces as [OperationCancelledException]; every other read failure
     * stays a [GitQueryException]. Timeout is termination but NOT a user cancel, so it stays
     * a query failure. Write commands never pass through here: they return a structured
     * [GitResult] so the business layer can record a ghost stash or partial checkout first.
     */
    private fun readFailure(result: GitResult): RuntimeException =
        if (result.failureKind == GitFailureKind.CANCELLED ||
            result.failureKind == GitFailureKind.INTERRUPTED
        ) {
            OperationCancelledException("git read cancelled: ${result.diagnostic()}")
        } else {
            GitQueryException(result)
        }

    override fun currentBranch(workDir: File): String? {
        val result = run(workDir, "symbolic-ref", "--short", "-q", "HEAD")
        return when {
            result.ok -> result.stdout.trim().ifEmpty { null }
            result.exitCode == 1 -> null
            else -> throw readFailure(result)
        }
    }

    override fun isGitRepo(workDir: File): Boolean {
        if (!workDir.isDirectory) return false
        val result = run(workDir, "rev-parse", "--show-toplevel")
        if (!result.ok) {
            if (!isNotGitRepoResult(workDir, result)) throw readFailure(result)
            return false
        }
        if (result.stdout.isBlank()) throw readFailure(result)
        val topLevel = runCatching { File(result.stdout).canonicalFile }.getOrNull() ?: return false
        val requested = runCatching { workDir.canonicalFile }.getOrNull() ?: return false
        return topLevel == requested
    }

    override fun repositoryIdentity(workDir: File): RepositoryIdentity? {
        if (!workDir.isDirectory) return null
        val result = run(workDir, "rev-parse", "--absolute-git-dir", "--show-superproject-working-tree")
        if (!result.ok) {
            if (!isNotGitRepoResult(workDir, result)) throw readFailure(result)
            return null
        }
        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val gitDirectory = lines.firstOrNull()?.let { path ->
            runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        } ?: throw readFailure(result)
        val superprojectRoot = lines.getOrNull(1)?.let { path ->
            runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        }
        return RepositoryIdentity(gitDirectory, superprojectRoot)
    }

    override fun runtimeInfo(workDir: File): GitRuntimeInfo {
        val result = run(workDir, "--version")
        if (!result.ok) throw readFailure(result)
        return GitRuntimeInfo(
            version = result.stdout.trim().ifEmpty { "unknown" },
            timeoutSeconds = processRunner.effectiveTimeoutSeconds,
        )
    }

    override fun isDirty(workDir: File): Boolean {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain")
        if (!result.ok) throw readFailure(result)
        return result.stdout.isNotBlank()
    }

    override fun dirtyFileCount(workDir: File): Int {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain")
        if (!result.ok) throw readFailure(result)
        return result.stdout.lines().count { it.isNotBlank() }
    }

    override fun isSubmoduleOnlyDirty(workDir: File): Boolean {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain=v2", "--untracked-files=normal")
        if (!result.ok) throw readFailure(result)
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
            if (result.failureKind != GitFailureKind.GIT_FAILED) throw readFailure(result)
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
        if (!result.ok) throw readFailure(result)
        val refs = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        return status.copy(
            localBranches = targetBranches.filterTo(linkedSetOf()) { "refs/heads/$it" in refs },
            remoteBranches = targetBranches.filterTo(linkedSetOf()) { "refs/remotes/$remote/$it" in refs },
        )
    }

    private fun inspectStatus(workDir: File): GitRepositoryInspection {
        val result = run(workDir, "--no-optional-locks", "status", "--porcelain=v2", "--branch", "--untracked-files=normal")
        if (!result.ok) throw readFailure(result)
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
        runIndexMutation(workDir, "stash", "push", "-u", "-m", message)

    override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult {
        if (paths.isEmpty()) return GitResult("stash paths", 0, "", "")
        return runIndexMutation(workDir, listOf("stash", "push", "-u", "-m", message, "--") + paths)
    }

    override fun fetch(workDir: File): GitResult = run(workDir, "fetch", "--prune")

    override fun localBranchExists(workDir: File, branch: String): Boolean {
        val result = run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch")
        return when {
            result.ok -> true
            result.exitCode == 1 -> false
            else -> throw readFailure(result)
        }
    }

    private fun remoteName(workDir: File): String {
        val key = workDir.absolutePath
        return remoteCache[key] ?: run {
            val result = run(workDir, "remote")
            if (!result.ok) throw readFailure(result)
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
            else -> throw readFailure(result)
        }
    }

    override fun untrackedFiles(workDir: File): List<String> {
        val result = run(workDir, "--no-optional-locks", "ls-files", "-z", "--others", "--exclude-standard")
        if (!result.ok) throw readFailure(result)
        // NUL-delimited raw paths (`-z` disables C-style quoting), so a file whose name
        // contains a tab, newline, or quote arrives whole — not as a quoted escape that a
        // later ls-tree pathspec could never match. Paths must not be trimmed: a file
        // literally named " leading.txt" keeps its leading space.
        return result.stdout.split('\u0000').filter { it.isNotEmpty() }
    }

    override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        // Match BranchCheckout's selection: a local branch wins, else the remote-tracking ref.
        val ref = if (localBranchExists(workDir, branch)) branch else "${remoteName(workDir)}/$branch"
        return structuralCollisions(workDir, ref, paths)
    }

    override fun headStructuralCollisions(workDir: File, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        return structuralCollisions(workDir, "HEAD", paths)
    }

    /**
     * Returns the input [paths] that structurally collide with [treeish]'s tree: a path that
     * is itself tracked (a file, or a directory the checkout needs to create), or whose
     * tracked ancestor is a file. Bounded: the query names the input paths AND their
     * ancestors as NON-recursive pathspecs, so it returns at most one entry per pathspec
     * regardless of repository size — never the whole tree (which could exceed the process
     * stdout limit). Git always lists a blob pathspec even when a deeper pathspec descends
     * into it, so a tracked-file ancestor is never missed; a tree ancestor may be elided,
     * which is harmless (a tracked directory ancestor is not a collision).
     */
    private fun structuralCollisions(workDir: File, treeish: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val pathSpecs = paths.flatMap { path -> ancestorPaths(path) + path }
        val typed = pathSpecs.chunked(PATHSPEC_CHUNK_SIZE).flatMap { chunk ->
            // `-z` disables C-style quoting so a pathspec whose name contains a tab/newline
            // matches the real entry, and the returned path is raw (identical to the input
            // that untrackedFiles produced, also under -z).
            val result = run(workDir, listOf("ls-tree", "-z", treeish, "--") + chunk)
            if (!result.ok) throw readFailure(result)
            parseLsTreeEntries(result.stdout)
        }
        val byPath = typed.associateBy { it.path }
        val colliding = mutableSetOf<String>()
        for (path in paths) {
            // Exact (a tracked file) or descendant (a tracked directory): the entry at the
            // path exists. Input paths are untracked FILES on disk, so they never nest under
            // each other and git returns the tree entry of any tracked-directory input.
            if (path in byPath) colliding += path
            // A tracked FILE that is a prefix of the path blocks it: checkout needs the file
            // where the worktree has a directory.
            if (ancestorPaths(path).any { it in byPath && byPath.getValue(it).type == LS_TREE_TYPE_BLOB }) {
                colliding += path
            }
        }
        return paths.filter { it in colliding }
    }

    private fun ancestorPaths(path: String): List<String> {
        val ancestors = mutableListOf<String>()
        var prefix = parentPath(path)
        while (prefix != null) {
            ancestors += prefix
            prefix = parentPath(prefix)
        }
        return ancestors
    }

    private fun parentPath(path: String): String? {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) null else path.substring(0, slash)
    }

    private data class LsTreeEntry(val type: String, val path: String)

    /**
     * Parses `git ls-tree -z` entries: `<mode> <type> <sha>\t<path>`, NUL-terminated. The
     * path is raw (unquoted), so it may itself contain tabs — the meta/path separator is the
     * FIRST tab, everything after it is the path verbatim.
     */
    private fun parseLsTreeEntries(stdout: String): List<LsTreeEntry> =
        stdout.split('\u0000')
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val tab = entry.indexOf('\t')
                if (tab < 0) return@mapNotNull null
                val meta = entry.substring(0, tab).split(' ')
                if (meta.size < 2) return@mapNotNull null
                LsTreeEntry(meta[1], entry.substring(tab + 1))
            }
            .toList()

    override fun checkoutExisting(workDir: File, branch: String): GitResult =
        runIndexMutation(workDir, "checkout", branch)

    override fun resetHard(workDir: File, revision: String): GitResult =
        runIndexMutation(workDir, "reset", "--hard", revision)

    override fun checkoutFromRemote(workDir: File, branch: String): GitResult =
        runIndexMutation(workDir, "checkout", "-b", branch, "${remoteName(workDir)}/$branch")

    override fun pullFf(workDir: File, branch: String): GitResult =
        runIndexMutation(workDir, "pull", "--ff-only", remoteName(workDir), branch)

    override fun submoduleSync(gitRoot: File): GitResult =
        run(gitRoot, "submodule", "sync", "--recursive")

    override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
        runIndexMutation(gitRoot, "submodule", "update", "--init", "--recursive", "--", path)

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
            throw readFailure(result)
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
        if (!result.ok && result.failureKind != GitFailureKind.GIT_FAILED) throw readFailure(result)
        return if (result.ok) result.stdout.trim().ifEmpty { null } else null
    }

    override fun stashTopOid(workDir: File): String? = revParseOptional(workDir, "--verify", "refs/stash")

    /** One entry of `git stash list`: its reflog selector, object id, and reflog subject. */
    private data class StashListEntry(val selector: String, val oid: String, val subject: String)

    /**
     * Parses `git stash list` once, keyed by selector/oid/subject. Fails closed: a non-zero
     * exit is a genuine query failure (`git stash list` exits 0 on an empty stash), and any
     * non-empty malformed row (fewer than two tab separators) is a parse failure — silently
     * skipping it would make [stashDrop] misread a missing oid as "already gone" and return
     * a false success.
     */
    private fun stashListEntries(workDir: File): List<StashListEntry> {
        val result = run(workDir, "stash", "list", "--format=%gd%x09%H%x09%gs")
        if (!result.ok) throw readFailure(result)
        val entries = mutableListOf<StashListEntry>()
        for (line in result.stdout.lineSequence()) {
            if (line.isEmpty()) continue
            val firstTab = line.indexOf('\t')
            val secondTab = if (firstTab < 0) -1 else line.indexOf('\t', firstTab + 1)
            if (secondTab < 0) {
                throw GitQueryException(GitResult("stash list", 1, "", "malformed stash list line: $line"))
            }
            entries += StashListEntry(
                selector = line.substring(0, firstTab),
                oid = line.substring(firstTab + 1, secondTab),
                // %gs is the reflog subject and may itself contain tabs; keep the rest verbatim.
                subject = line.substring(secondTab + 1),
            )
        }
        return entries
    }

    override fun stashOidByMessage(workDir: File, messagePrefix: String): String? {
        // %gs is the stash reflog subject ("On <branch>: <message>"), so matching is
        // scoped to stashes this plugin created rather than whatever sits on top of
        // refs/stash (a concurrent external `git stash push` must not be misapplied).
        return stashListEntries(workDir).firstOrNull { it.subject.contains(messagePrefix) }?.oid
    }

    override fun stashApply(workDir: File, oid: String): GitResult =
        runIndexMutation(workDir, "stash", "apply", oid)

    override fun stashDrop(workDir: File, oid: String): GitResult {
        // `git stash drop` requires a stash@{n} selector — a bare OID is rejected with
        // "error: '<oid>' is not a stash reference" (apply accepts either). Git porcelain
        // has no atomic drop-by-oid, so the selector is resolved and re-verified at drop
        // time; if the mapping does not stabilize across one retry the drop is refused. This
        // narrows — it cannot fully close — the window where a concurrent external
        // `git stash push` shifts stash@{n} between two commands, so the drop ultimately
        // executes against a (re-verified) stack selector.
        repeat(2) {
            val entries = try {
                stashListEntries(workDir)
            } catch (e: GitQueryException) {
                return e.result
            }
            val selector = entries.firstOrNull { it.oid == oid }?.selector
                ?: return GitResult("stash drop", 0, "", "") // already gone → idempotent success
            val confirm = try {
                stashListEntries(workDir)
            } catch (e: GitQueryException) {
                return e.result
            }
            if (confirm.firstOrNull { it.selector == selector }?.oid == oid) {
                return run(workDir, "stash", "drop", selector)
            }
            // The selector changed between the two reads; retry once with a fresh resolution.
        }
        return GitResult("stash drop", 1, "", "stash selector for $oid changed between reads; refusing to drop")
    }

    override fun checkoutNewBranch(workDir: File, branch: String): GitResult =
        runIndexMutation(workDir, "checkout", "-b", branch)

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
        if (!result.ok) throw readFailure(result)
        val inspection = parsePorcelainV2Status(result.stdout)
        return HeadAndBranch(inspection.head, inspection.currentBranch)
    }

    override fun listAllBranches(workDir: File): List<String> {
        val remote = remoteName(workDir)
        val result = run(
            workDir,
            "for-each-ref",
            "--format=%(refname)",
            "refs/heads",
            "refs/remotes/$remote",
        )
        if (!result.ok) throw readFailure(result)
        // %(refname) yields full refs, so the namespace prefix — not a name-based string check —
        // decides the shape: refs/heads/* is a local branch, the exact refs/remotes/<remote>/HEAD
        // is the symbolic default (dropped, it is not a branch), and any other refs/remotes/<remote>/*
        // is a remote-tracking branch. A short ref here would conflate a local branch named
        // origin/<x> with a remote-tracking one and silently drop legal refs whose name merely
        // begins with the remote's HEAD (e.g. HEAD-feature or HEAD/foo).
        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ref ->
                when {
                    ref == "refs/remotes/$remote/HEAD" -> null // symbolic default-remote HEAD
                    ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
                    ref.startsWith("refs/remotes/$remote/") -> ref.removePrefix("refs/remotes/$remote/")
                    else -> null
                }
            }
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
        private const val LS_TREE_TYPE_BLOB = "blob"
        private const val MAX_SUBMODULE_DEPTH = 10
        private const val SUBMODULE_ENTRY_KEY_REGEX = "^submodule\\..*\\.(path|url)$"
        private const val SUBMODULE_KEY_PREFIX = "submodule."
        private const val SUBMODULE_PATH_SUFFIX = ".path"
        private const val SUBMODULE_URL_SUFFIX = ".url"
        private val LOG = IdeaLogger.getInstance("SubmoduleBranchSwitcher")
    }
}
