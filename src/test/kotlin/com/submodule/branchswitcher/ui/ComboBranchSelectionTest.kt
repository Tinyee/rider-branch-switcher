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
 * Pins what a branch combo currently stands for, which is not simply its `selectedItem`. Two
 * cases diverge from the raw selection: a combo whose load is cancelled keeps showing the literal
 * [LOADING_BRANCH] placeholder (the displaced value is recovered from the load seed), and an
 * editable combo holds a field edit that Swing only commits to `selectedItem` on Enter or focus
 * loss (the live field text is the answer). Reading `selectedItem` directly yields the placeholder
 * as a branch name, or reports a branch the user already cleared as still current — which let the
 * editor enable Save on an empty draft.
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
        setComboOfferedBranches(combo, listOf("draft-branch", "main"))
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

    /**
     * A JComboBox commits the field to [JComboBox.getSelectedItem] only on Enter or focus loss,
     * so a user who clears the branch and clicks straight on Save (or collapses the preset) has
     * an empty field but an unchanged selection. Reading only `selectedItem` reported the stale
     * branch: the editor kept calling the draft valid and lit Save, which then failed with
     * "invalid branch for '<path>': ''".
     */
    @Test
    fun `a branch cleared in the field reads as cleared before the edit is committed`() {
        val combo = JComboBox(arrayOf("dev", "main"))
        combo.isEditable = true
        combo.selectedItem = "dev"
        assertEquals("precondition: the selection is the committed branch", "dev", comboBranchSelection(combo))

        editorFieldOf(combo).text = ""
        assertEquals("the cleared field is the branch now", "", comboBranchSelection(combo))
        assertEquals("and the combo itself still holds the old value", "dev", combo.selectedItem)
    }

    @Test
    fun `a branch typed into the field reads as typed before the edit is committed`() {
        val combo = JComboBox(arrayOf("dev", "main"))
        combo.isEditable = true
        combo.selectedItem = "dev"

        editorFieldOf(combo).text = "feature/new"

        assertEquals("feature/new", comboBranchSelection(combo))
    }

    @Test
    fun `a field holding the placeholder does not shadow the seeded branch`() {
        val combo = displayableCombo()
        combo.model = javax.swing.DefaultComboBoxModel(arrayOf("draft-branch", "main"))
        combo.selectedItem = "draft-branch"

        startLoad(combo)

        assertEquals(
            "mid-load the field shows Loading..., so the seed is the answer, not the field",
            "draft-branch",
            comboBranchSelection(combo),
        )
    }

    private fun editorFieldOf(combo: JComboBox<String>): javax.swing.JTextField =
        combo.editor.editorComponent as javax.swing.JTextField

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
