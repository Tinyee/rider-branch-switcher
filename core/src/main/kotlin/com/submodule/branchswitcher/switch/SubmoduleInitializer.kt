package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.RepositoryIdentity
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
        val issue: OperationIssue? = null,
        val initializedBySwitch: Boolean = false,
        val repositoryIdentity: RepositoryIdentity? = null,
    )

    fun prepare(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        registrationRoot: File,
        registrationPath: String,
    ): Result {
        // Cancellation aborts at the entry, before any Git query or side effect, and
        // cannot bypass the pre-approval check below.
        context.operationControl?.checkCancelled()
        if (context.git.isGitRepo(directory)) {
            return verifyReady(context, target, directory, initializedBySwitch = false)
        }
        // The user pre-approved the missing directories before the switch acquired the
        // write lease; a path outside that set is declined (fail closed) regardless of
        // whether a cancellation request is in flight.
        if (context.confirmBeforeInit && target.path !in context.preApprovedSubmoduleInit) {
            context.log.info("[skip] init declined for ${target.path}")
            return Result(
                ready = false,
                issue = initIssue(target.path, OperationIssueCode.SUBMODULE_INIT_DECLINED),
            )
        }

        context.log.info(
            "dir missing, trying: git submodule update --init --recursive -- $registrationPath " +
                "(${target.path})",
        )
        val initResult = context.git.submoduleInitPath(registrationRoot, registrationPath)
        if (!initResult.ok) {
            context.log.warn("[skip] submodule init failed: ${initResult.diagnostic()}")
            return Result(
                ready = false,
                issue = initIssue(
                    target.path,
                    OperationIssueCode.SUBMODULE_INIT_FAILED,
                    initResult.diagnostic(),
                ),
            )
        }
        return verifyReady(context, target, directory, initializedBySwitch = true)
    }

    private fun verifyReady(
        context: SwitchContext,
        target: RepoTarget,
        directory: File,
        initializedBySwitch: Boolean,
    ): Result {
        if (!directory.exists()) {
            context.log.warn("[skip] ${target.path} initialization completed without creating its directory")
            return Result(
                ready = false,
                issue = initIssue(target.path, OperationIssueCode.SUBMODULE_DIRECTORY_MISSING),
            )
        }
        if (!context.git.isGitRepo(directory)) {
            context.log.warn("[skip] ${target.path} initialization completed without a usable Git repository")
            return Result(
                ready = false,
                issue = initIssue(target.path, OperationIssueCode.SUBMODULE_REPOSITORY_MISSING),
            )
        }
        val identity = context.git.repositoryIdentity(directory)
        if (identity == null) {
            context.log.warn("[skip] ${target.path} repository identity is unavailable")
            return Result(
                ready = false,
                issue = initIssue(target.path, OperationIssueCode.REPOSITORY_IDENTITY_UNAVAILABLE),
            )
        }
        if (initializedBySwitch) {
            context.log.info("submodule init ok; the new worktree will be retained if a later step fails")
        }
        return Result(
            ready = true,
            initializedBySwitch = initializedBySwitch,
            repositoryIdentity = identity,
        )
    }

    private fun initIssue(
        path: String,
        code: OperationIssueCode,
        diagnostic: String? = null,
    ) = OperationIssue(
        stage = OperationStage.SUBMODULE_INIT,
        code = code,
        repositoryPath = path,
        diagnostic = diagnostic,
    )
}
