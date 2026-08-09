package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.rethrowIfCancellation
import java.io.File

/** Records reproducibility data without allowing diagnostics to change workflow behavior. */
@Suppress("TooGenericExceptionCaught") // optional diagnostics must never fail the owning Git workflow
internal fun AppLogger.logGitRuntime(
    git: GitRepositoryQuery,
    workDir: File,
    cancellationClassifier: CancellationClassifier,
) {
    val runtime = try {
        git.runtimeInfo(workDir)
    } catch (error: RuntimeException) {
        cancellationClassifier.rethrowIfCancellation(error)
        logFailure("runtime inspection failed", error)
        null
    }
    info(
        "runtime: git=${runtime?.version ?: "unknown"}, " +
            "commandTimeout=${runtime?.timeoutSeconds?.let { "${it}s" } ?: "unknown"}",
    )
}
