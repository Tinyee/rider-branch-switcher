package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/**
 * Makes sure a checkout target is a usable repository.
 *
 * Missing submodules may be initialized only after the main checkout succeeds,
 * because the main branch owns both `.gitmodules` and the submodule gitlink.
 */
internal object SubmoduleInitializer {
    data class Result(
        val ready: Boolean,
        val failure: String? = null,
        val initializedBySwitch: Boolean = false,
    )

    fun prepare(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        mainCheckoutSucceeded: Boolean,
    ): Result {
        val isMain = target.path == "."

        if (!isMain && !context.git.isGitRepo(directory) && mainCheckoutSucceeded) {
            if (context.confirmBeforeInit && context.cancellationHandle?.isCanceled != true) {
                val confirmed = context.onConfirmSubmoduleInit?.invoke(target.path) ?: false
                if (!confirmed) {
                    context.log.info("[skip] init declined for ${target.path}")
                    return Result(ready = false, failure = "init declined")
                }
            }

            context.log.info(
                "dir missing, trying: git submodule update --init --recursive -- ${target.path}",
            )
            val initResult = context.git.submoduleInitPath(context.projectRoot.toFile(), target.path)
            if (!initResult.ok) {
                context.log.warn("[skip] submodule init failed: ${initResult.diagnostic()}")
                return Result(ready = false, failure = "submodule init failed")
            }
            context.log.info("submodule init ok; the new worktree will be retained if a later step fails")
            return Result(ready = true, initializedBySwitch = true)
        }

        if (!directory.exists()) {
            context.log.info("[skip] dir not found: ${directory.absolutePath}")
            return Result(ready = false, failure = "dir not found")
        }
        if (!context.git.isGitRepo(directory)) {
            context.log.info("[skip] not a git repo")
            return Result(ready = false, failure = "not a git repo")
        }
        return Result(ready = true)
    }
}
