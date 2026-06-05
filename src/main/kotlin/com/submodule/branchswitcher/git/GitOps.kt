package com.submodule.branchswitcher.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.charset.StandardCharsets

class GitOps : GitClient {
    private fun run(workDir: File, vararg args: String): GitResult {
        val cmd = GeneralCommandLine("git", *args)
            .withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
        val handler = CapturingProcessHandler(cmd)
        val out = handler.runProcess(60_000)
        return GitResult(
            cmd = "git ${args.joinToString(" ")}",
            exitCode = out.exitCode,
            stdout = out.stdoutLines.joinToString("\n"),
            stderr = out.stderrLines.joinToString("\n"),
        )
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

    override fun stash(workDir: File, message: String): GitResult =
        run(workDir, "stash", "push", "-u", "-m", message)

    override fun fetch(workDir: File): GitResult = run(workDir, "fetch", "--prune")

    override fun localBranchExists(workDir: File, branch: String): Boolean =
        run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$branch").ok

    override fun remoteBranchExists(workDir: File, branch: String): Boolean =
        run(workDir, "show-ref", "--verify", "--quiet", "refs/remotes/origin/$branch").ok

    override fun checkoutExisting(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", branch)

    override fun checkoutFromRemote(workDir: File, branch: String): GitResult =
        run(workDir, "checkout", "-b", branch, "origin/$branch")

    override fun pullFf(workDir: File, branch: String): GitResult =
        run(workDir, "pull", "--ff-only", "origin", branch)

    override fun submoduleSync(gitRoot: File): GitResult =
        run(gitRoot, "submodule", "sync", "--recursive")

    override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
        run(gitRoot, "submodule", "update", "--init", "--", path)

    private val PATH_LINE = Regex("""^path\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    override fun listSubmodulePaths(gitRoot: File): List<String> {
        val file = File(gitRoot, ".gitmodules")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@mapNotNull null
            val v = PATH_LINE.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            v.trim().trim('"').takeIf { it.isNotEmpty() }
        }
    }

    override fun listAllBranches(workDir: File): List<String> {
        val r = run(workDir, "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads", "refs/remotes/origin")
        if (!r.ok) return emptyList()
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "origin/HEAD" && !it.startsWith("origin/HEAD") }
            .map { if (it.startsWith("origin/")) it.removePrefix("origin/") else it }
            .distinct()
            .sorted()
    }
}
