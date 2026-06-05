package com.hsmahjong.branchswitcher

import java.io.File
import java.nio.file.Path

enum class DirtyAction { Stash, Skip, Force }

data class SwitchOptions(
    val dirty: DirtyAction = DirtyAction.Stash,
    val pull: Boolean = true,
    val fetchFirst: Boolean = true,
)

class SwitchExecutor(
    private val projectRoot: Path,
    private val log: (String) -> Unit,
) {
    fun execute(preset: Preset, options: SwitchOptions): Boolean {
        log("=== switching to preset: ${preset.name} ===")
        var allOk = true
        for (target in preset.targets()) {
            val dir = if (target.path == ".") projectRoot.toFile()
            else projectRoot.resolve(target.path).toFile()
            val label = if (target.path == ".") "<main>" else target.path
            log("")
            log("--- $label  →  ${target.branch} ---")
            if (!dir.exists()) {
                log("[skip] dir not found: ${dir.absolutePath}")
                allOk = false
                continue
            }
            if (!dir.resolve(".git").exists() && !File(dir, ".git").isFile) {
                log("[skip] not a git repo")
                allOk = false
                continue
            }

            val cur = GitOps.currentBranch(dir)
            log("current: ${cur ?: "(detached)"}")

            if (GitOps.isDirty(dir)) {
                when (options.dirty) {
                    DirtyAction.Skip -> {
                        log("[skip] working tree dirty")
                        allOk = false
                        continue
                    }
                    DirtyAction.Stash -> {
                        val r = GitOps.stash(dir, "branch-switcher: before -> ${target.branch}")
                        log("stash: ${if (r.ok) "ok" else "FAIL"}")
                        if (r.stderr.isNotBlank()) log(r.stderr)
                        if (!r.ok) {
                            allOk = false
                            continue
                        }
                    }
                    DirtyAction.Force -> log("[force] proceeding with dirty tree")
                }
            }

            if (options.fetchFirst) {
                val f = GitOps.fetch(dir)
                if (!f.ok) log("fetch warn: ${f.stderr}")
            }

            val checkoutResult = if (GitOps.localBranchExists(dir, target.branch)) {
                GitOps.checkoutExisting(dir, target.branch)
            } else if (GitOps.remoteBranchExists(dir, target.branch)) {
                log("local branch missing, creating from origin/${target.branch}")
                GitOps.checkoutFromRemote(dir, target.branch)
            } else {
                log("[fail] branch '${target.branch}' not found locally or on origin")
                allOk = false
                continue
            }
            if (!checkoutResult.ok) {
                log("[fail] checkout: ${checkoutResult.stderr}")
                allOk = false
                continue
            }
            log("checkout ok")

            if (options.pull && preset.pull) {
                val p = GitOps.pullFf(dir, target.branch)
                if (p.ok) {
                    log("pull ok")
                } else {
                    log("[warn] pull failed (kept local): ${p.stderr.lines().firstOrNull() ?: ""}")
                }
            }
        }
        log("")
        log(if (allOk) "=== done ===" else "=== done with errors ===")
        return allOk
    }
}
