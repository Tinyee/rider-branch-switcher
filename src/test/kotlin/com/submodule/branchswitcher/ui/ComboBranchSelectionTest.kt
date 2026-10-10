package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComboBox

/**
 * Pins the load-seed rule: a combo whose load is cancelled keeps showing the literal
 * [LOADING_BRANCH] placeholder, so every read of "what branch does this combo stand for"
 * must go through [comboBranchSelection] and recover the value the load displaced. Reading
 * `selectedItem` directly yields the placeholder — which would commit "Loading..." as a
 * branch name, or let a collapse/re-expand cycle revert an unsaved edit.
 */
class ComboBranchSelectionTest {

    private val loadCompletionTimeoutSeconds = 30L

    @Test
    fun `a combo with a real selection reports it`() {
        val combo = JComboBox(arrayOf("main", "dev"))
        combo.selectedItem = "dev"

        assertEquals("dev", comboBranchSelection(combo))
    }

    @Test
    fun `a combo left on the loading placeholder reports the branch the load displaced`() {
        val combo = displayableCombo()
        combo.model = javax.swing.DefaultComboBoxModel(arrayOf("draft-branch", "main"))
        combo.selectedItem = "draft-branch"
        combo.putClientProperty(KEY_ALL_BRANCHES, listOf("draft-branch", "main"))
        // A load that never completes (cancelled by a collapse) leaves this state behind.
        startLoad(combo)
        assertEquals(
            "the combo itself only shows the placeholder",
            LOADING_BRANCH,
            combo.selectedItem,
        )

        assertEquals(
            "the displaced branch must survive the placeholder",
            "draft-branch",
            comboBranchSelection(combo),
        )
    }

    @Test
    fun `a completed load clears the seed so a later placeholder has nothing stale to report`() {
        val combo = displayableCombo()
        combo.selectedItem = "dev"
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, ComboBranchTarget(java.io.File("."), "dev"),
            fixedBranchLoads(listOf("main", "dev")), createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { _, _ -> finished.countDown() },
            scheduleUi = { it() },
        )
        assertTrue("load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals("dev", comboBranchSelection(combo))

        // A later refresh starts from the completed value, never from a stale seed.
        startLoad(combo)
        assertEquals("dev", comboBranchSelection(combo))
    }

    @Test
    fun `an empty combo stays empty rather than reporting the placeholder`() {
        val combo = displayableCombo()
        startLoad(combo)

        assertEquals("an unset combo has no branch, not a loading one", "", comboBranchSelection(combo).orEmpty())
        assertNotEquals(LOADING_BRANCH, comboBranchSelection(combo))
    }

    /** Starts a load whose discovery never completes, leaving the combo on the placeholder. */
    private fun startLoad(combo: JComboBox<String>) {
        val never = CountDownLatch(1)
        val started = CountDownLatch(1)
        loadComboBranches(
            combo, ComboBranchTarget(java.io.File("."), comboBranchSelection(combo).orEmpty()),
            BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                branchOperation {
                    started.countDown()
                    never.await()
                    emptyList()
                }
            },
            createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { _, _ -> },
            scheduleUi = { it() },
        )
        assertTrue("load should start", started.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
    }

    private fun displayableCombo(): JComboBox<String> =
        object : JComboBox<String>() {
            override fun isDisplayable(): Boolean = true
        }

    private fun fixedBranchLoads(branches: List<String>): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            branchOperation { branches }
        }

    private fun branchOperation(load: () -> List<String>): GitOperationSession {
        val cancelled = AtomicBoolean(false)
        return Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listAllBranches" -> if (cancelled.get()) throw CancellationException("cancelled") else load()
                "cancel" -> cancelled.set(true)
                "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession
    }
}
