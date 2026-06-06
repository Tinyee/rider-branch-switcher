package com.submodule.branchswitcher.git

import java.io.File

data class GitResult(
    val cmd: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

interface GitClient {
    fun currentBranch(workDir: File): String?
    fun isDirty(workDir: File): Boolean
    fun dirtyFileCount(workDir: File): Int
    fun stash(workDir: File, message: String): GitResult
    fun fetch(workDir: File): GitResult
    fun localBranchExists(workDir: File, branch: String): Boolean
    fun remoteBranchExists(workDir: File, branch: String): Boolean
    fun checkoutExisting(workDir: File, branch: String): GitResult
    fun checkoutFromRemote(workDir: File, branch: String): GitResult
    fun pullFf(workDir: File, branch: String): GitResult
    fun submoduleSync(gitRoot: File): GitResult
    fun submoduleInitPath(gitRoot: File, path: String): GitResult
    fun listSubmodulePaths(gitRoot: File): List<String>
    fun listAllBranches(workDir: File): List<String>
    fun revParseHead(workDir: File): String?
    fun stashPop(workDir: File): GitResult
    fun checkoutNewBranch(workDir: File, branch: String): GitResult
}
