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
            context.progressHandle?.apply {
                fraction = idx.toDouble() / total
                text2 = if (target.path == ".") context.projectRoot.fileName.toString() else target.path
            }
            context.cancellationHandle?.checkCanceled()
            val isMain = target.path == "."
            val dir = resolveGitDir(context.projectRoot, target.path)
            val label = if (isMain) context.projectRoot.fileName.toString() else target.path

            // Skip paths marked by DirtyHandlingStep (skip / stash-fail)
            if (target.path in context.skippedPaths) {
                context.log.info("[skip] $label — skipped by dirty handling")
                continue
            }

            context.log.info("")
            context.log.info("--- $label  →  ${target.branch} ---")

            // Submodule init for missing directories (after main checkout only)
            if (!isMain && !context.git.isGitRepo(dir)) {
                if (mainCheckoutOk) {
                    if (context.confirmBeforeInit && context.cancellationHandle?.isCanceled != true) {
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
                            context.log.info("[skip] init declined for ${target.path}")
                            failures[target.path] = "init declined"
                            continue
                        }
                    }
                    context.log.info("dir missing, trying: git submodule update --init -- ${target.path}")
                    val r = context.git.submoduleInitPath(context.projectRoot.toFile(), target.path)
                    if (!r.ok) {
                        context.log.warn("[skip] submodule init failed: ${r.stderr.lines().firstOrNull() ?: ""}")
                        failures[target.path] = "submodule init failed"
                        continue
                    }
                    context.log.info("submodule init ok")
                }
            }

            // Check directory
            if (!dir.exists()) {
                context.log.info("[skip] dir not found: ${dir.absolutePath}")
                failures[target.path] = "dir not found"
                continue
            }
            if (!context.git.isGitRepo(dir)) {
                context.log.info("[skip] not a git repo")
                failures[target.path] = "not a git repo"
                continue
            }

            val cur = context.git.currentBranch(dir)
            context.log.info("current: ${cur ?: "(detached)"}")
            if (cur != null && cur == target.branch) {
                context.log.info("already on '${target.branch}', skipping checkout")
                if (isMain) mainCheckoutOk = true
                context.successfulCheckouts.add(target.path)
                continue
            }

            // Perform checkout
            val checkoutResult = if (context.git.localBranchExists(dir, target.branch)) {
                context.git.checkoutExisting(dir, target.branch)
            } else if (context.git.remoteBranchExists(dir, target.branch)) {
                context.log.info("local branch missing, creating from origin/${target.branch}")
                context.git.checkoutFromRemote(dir, target.branch)
            } else {
                context.log.warn("[fail] branch '${target.branch}' not found locally or on origin")
                failures[target.path] = "branch not found"
                // Still try to pop stash if this path was stashed — don't leave orphaned stashes
                context.stashedPaths.remove(target.path)?.let { msg ->
                    val popResult = context.git.stashPop(dir)
                    if (popResult.ok) {
                        context.log.info("stash pop ok (recovered after branch-not-found: $msg)")
                    } else {
                        context.log.warn("[fail] stash pop also failed: ${popResult.stderr.lines().firstOrNull() ?: ""}")
                        failures[target.path] = "branch not found + stash pop failed"
                    }
                }
                continue
            }
            if (!checkoutResult.ok) {
                context.log.warn("[fail] checkout: ${checkoutResult.stderr}")
                failures[target.path] = "checkout failed"
                continue
            }
            context.log.info("checkout ok")
            if (isMain) mainCheckoutOk = true
            context.successfulCheckouts.add(target.path)
        }
        return if (failures.isEmpty()) StepResult.Success else StepResult.Partial(failures)
    }
}
