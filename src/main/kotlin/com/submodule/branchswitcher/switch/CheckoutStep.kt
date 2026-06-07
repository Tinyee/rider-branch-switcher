package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.Bundle
import java.io.File

/**
 * Checkout each target to its target branch, processing main first then submodules.
 * For submodules with missing directories, attempts `git submodule update --init`
 * after main checkout has succeeded. Also reports current branch and logs per-target progress.
 */
class CheckoutStep : SwitchStep {
    override val name = "checkout"

    override fun execute(context: SwitchContext): StepResult {
        val failures = LinkedHashMap<String, String>()
        var mainCheckoutOk = false
        val targets = context.preset.targets()
        val total = targets.size

        for ((idx, target) in targets.withIndex()) {
            context.indicator?.apply {
                fraction = idx.toDouble() / total
                text2 = if (target.path == ".") context.projectRoot.fileName.toString() else target.path
                checkCanceled()
            }
            val isMain = target.path == "."
            val dir = resolveGitDir(context.projectRoot, target.path)
            val label = if (isMain) context.projectRoot.fileName.toString() else target.path
            context.log("")
            context.log("--- $label  →  ${target.branch} ---")

            // Submodule init for missing directories (after main checkout only)
            if (!isMain && !isGitRepo(dir)) {
                if (mainCheckoutOk) {
                    if (context.confirmBeforeInit && context.indicator?.isCanceled != true) {
                        val result = java.util.concurrent.atomic.AtomicInteger(com.intellij.openapi.ui.Messages.NO)
                        com.intellij.openapi.application.ApplicationManager.getApplication()
                            .invokeAndWait {
                                result.set(com.intellij.openapi.ui.Messages.showYesNoDialog(
                                    Bundle.msg("dialog.init.submodule", target.path),
                                    Bundle.msg("dialog.init.title"),
                                    com.intellij.openapi.ui.Messages.getQuestionIcon(),
                                ))
                            }
                        if (result.get() != com.intellij.openapi.ui.Messages.YES) {
                            context.log("[skip] init declined for ${target.path}")
                            failures[target.path] = "init declined"
                            continue
                        }
                    }
                    context.log("dir missing, trying: git submodule update --init -- ${target.path}")
                    val r = context.git.submoduleInitPath(context.projectRoot.toFile(), target.path)
                    if (!r.ok) {
                        context.log("[skip] submodule init failed: ${r.stderr.lines().firstOrNull() ?: ""}")
                        failures[target.path] = "submodule init failed"
                        continue
                    }
                    context.log("submodule init ok")
                }
            }

            // Check directory
            if (!dir.exists()) {
                context.log("[skip] dir not found: ${dir.absolutePath}")
                failures[target.path] = "dir not found"
                continue
            }
            if (!isGitRepo(dir)) {
                context.log("[skip] not a git repo")
                failures[target.path] = "not a git repo"
                continue
            }

            val cur = context.git.currentBranch(dir)
            context.log("current: ${cur ?: "(detached)"}")
            if (cur != null && cur == target.branch) {
                context.log("already on '${target.branch}', skipping checkout")
                if (isMain) mainCheckoutOk = true
                continue
            }

            // Perform checkout
            val checkoutResult = if (context.git.localBranchExists(dir, target.branch)) {
                context.git.checkoutExisting(dir, target.branch)
            } else if (context.git.remoteBranchExists(dir, target.branch)) {
                context.log("local branch missing, creating from origin/${target.branch}")
                context.git.checkoutFromRemote(dir, target.branch)
            } else {
                context.log("[fail] branch '${target.branch}' not found locally or on origin")
                failures[target.path] = "branch not found"
                continue
            }
            if (!checkoutResult.ok) {
                context.log("[fail] checkout: ${checkoutResult.stderr}")
                failures[target.path] = "checkout failed"
                continue
            }
            context.log("checkout ok")
            if (isMain) mainCheckoutOk = true
            // Auto-pop stash if this path was stashed before switching
            context.stashedPaths.remove(target.path)?.let { msg ->
                val popResult = context.git.stashPop(dir)
                if (popResult.ok) {
                    context.log("stash pop ok ($msg)")
                } else {
                    context.log("[fail] stash pop failed: ${popResult.stderr.lines().firstOrNull() ?: ""}")
                    failures[target.path] = "stash pop failed"
                }
            }
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
