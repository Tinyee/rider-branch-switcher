package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RepositoryStateRefreshCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `superseded refresh cancels its Git session and cannot deliver stale state`() {
        val root = temp.newFolder("root")
        val firstStarted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val latestDelivered = CountDownLatch(1)
        val openCount = AtomicInteger()
        val deliveredBranches = mutableListOf<String?>()
        val coordinator = RepositoryStateRefreshCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            openOperation = {
                if (openCount.getAndIncrement() == 0) {
                    stateOperation(
                        branch = {
                            firstStarted.countDown()
                            while (!firstCancelled.get()) Thread.sleep(10)
                            throw CancellationException("superseded")
                        },
                        onCancel = { firstCancelled.set(true) },
                    )
                } else {
                    stateOperation(branch = { "latest" })
                }
            },
            detector = RepositoryStateDetector(createStringAppender {}),
            log = createStringAppender {},
            deliver = { it() },
        )

        coordinator.refresh(root.toPath(), listOf(".")) { snapshot ->
            deliveredBranches += snapshot.branches["."]
        }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        coordinator.refresh(root.toPath(), listOf(".")) { snapshot ->
            deliveredBranches += snapshot.branches["."]
            latestDelivered.countDown()
        }

        assertTrue(latestDelivered.await(5, TimeUnit.SECONDS))
        assertTrue(firstCancelled.get())
        assertEquals(listOf("latest"), deliveredBranches)
        coordinator.close()
    }

    private fun stateOperation(
        branch: () -> String,
        onCancel: () -> Unit = {},
    ): GitOperationSession = Proxy.newProxyInstance(
        GitOperationSession::class.java.classLoader,
        arrayOf(GitOperationSession::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "currentBranch" -> branch()
            "isDirty" -> false
            "isGitRepo" -> true
            "cancel" -> onCancel()
            "close" -> Unit
            "registeredSubmodules", "listAllBranches", "listSubmodulePaths" -> emptyList<Any>()
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    } as GitOperationSession
}
