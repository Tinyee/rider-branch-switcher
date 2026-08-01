package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.log.AppLogger
import java.io.File

/** Records reproducibility data without allowing diagnostics to change workflow behavior. */
@Suppress("TooGenericExceptionCaught") // optional diagnostics must never fail the owning Git workflow
internal fun AppLogger.logGitRuntime(git: GitRepositoryQuery, workDir: File) {
    val runtime = try {
        git.runtimeInfo(workDir)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
        throw cancelled
    } catch (error: RuntimeException) {
        warn("runtime inspection failed", error)
        null
    }
    info(
        "runtime: git=${runtime?.version ?: "unknown"}, " +
            "commandTimeout=${runtime?.timeoutSeconds?.let { "${it}s" } ?: "unknown"}",
    )
}
