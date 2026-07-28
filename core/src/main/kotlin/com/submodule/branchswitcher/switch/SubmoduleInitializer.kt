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
    )

    fun prepare(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        mainCheckoutSucceeded: Boolean,
    ): Result {
        val isMain = target.path == "."
        var warning: String? = null

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
            context.log.info("submodule init ok")

            // The earlier submodule FetchStep skipped this missing repository,
            // so a new worktree needs its own fetch before branch discovery.
            if (context.options.fetchFirst) {
                val fetchResult = context.git.fetch(directory)
                if (!fetchResult.ok) {
                    context.log.warn(
                        "fetch after init warn: ${fetchResult.diagnostic()} (${target.path})",
                    )
                    warning = "fetch after init had warnings"
                }
            }
        }

        if (!directory.exists()) {
            context.log.info("[skip] dir not found: ${directory.absolutePath}")
            return Result(ready = false, failure = "dir not found")
        }
        if (!context.git.isGitRepo(directory)) {
            context.log.info("[skip] not a git repo")
            return Result(ready = false, failure = "not a git repo")
        }
        return Result(ready = true, failure = warning)
    }
}
