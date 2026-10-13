package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.EventQueue
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JTextField

class BranchComboUtilTest {

    // Windows CI runners can be slow to schedule the shared IO dispatcher, so a
    // trivially-synchronous branch load may take longer than a tight 5s latch. Keep a
    // generous window; a healthy load still completes in milliseconds.
    private val loadCompletionTimeoutSeconds = 30L

    @Test
    fun `branch popup filter is case insensitive and preserves typed text`() {
        val combo = JComboBox(arrayOf("main", "feature/login", "Feature/Search"))
        combo.isEditable = true
        setComboOfferedBranches(combo, listOf("main", "feature/login", "Feature/Search"))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = "FEATURE"
        editor.caretPosition = editor.text.length

        filterBranchPopup(combo, editor)

        assertEquals(2, combo.itemCount)
        assertEquals("feature/login", combo.getItemAt(0))
        assertEquals("Feature/Search", combo.getItemAt(1))
        assertEquals("FEATURE", editor.text)
        assertEquals(editor.text.length, editor.caretPosition)
    }

    @Test
    fun `branch popup filter restores full list for blank text`() {
        val combo = JComboBox(arrayOf("feature/login"))
        combo.isEditable = true
        setComboOfferedBranches(combo, listOf("main", "dev", "feature/login"))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = ""

        filterBranchPopup(combo, editor)

        assertEquals(listOf("main", "dev", "feature/login"), (0 until combo.itemCount).map(combo::getItemAt))
    }

    @Test
    fun `branch choices add missing current branch and remove duplicates`() {
        val result = mergeBranchChoices("feature/current", listOf("main", "main", "dev"))

        assertEquals(listOf("feature/current", "main", "dev"), result)
    }

    @Test
    fun `branch choices never expose loading placeholder or blanks`() {
        val result = mergeBranchChoices(LOADING_BRANCH, listOf("", " ", LOADING_BRANCH, "main"))

        assertEquals(listOf("main"), result)
        assertFalse(result.contains(LOADING_BRANCH))
    }

    @Test
    fun `branch choices keep existing current position`() {
        val result = mergeBranchChoices("dev", listOf("main", "dev", "feature"))

        assertEquals(listOf("main", "dev", "feature"), result)
    }

    @Test
    fun `branch load restores combo and completes loading`() {
        val combo = displayableCombo()
        var starts = 0
        var ends = 0
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "dev"),
            branchLoads { listOf("main", "dev") }, createStringAppender {},
            onLoadStart = { starts++ },
            onLoadEnd = { _, _ ->
                ends++
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("branch load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals(1, starts)
        assertEquals(1, ends)
        assertTrue(combo.isEnabled)
        assertEquals(listOf("main", "dev"), (0 until combo.itemCount).map(combo::getItemAt))
    }

    @Test
    fun `branch load exception restores combo and completes loading`() {
        val combo = displayableCombo()
        val logs = mutableListOf<String>()
        var ends = 0
        var loadSucceeded: Boolean? = null
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "dev"),
            branchLoads { error("broken") }, createStringAppender { logs += it },
            onLoadStart = {},
            onLoadEnd = { succeeded, _ ->
                ends++
                loadSucceeded = succeeded
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("failed branch load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals(1, ends)
        assertFalse("failed load must report success=false so callers can retry", loadSucceeded!!)
        assertTrue(combo.isEnabled)
        assertEquals(listOf("dev"), (0 until combo.itemCount).map(combo::getItemAt))
        assertTrue(logs.any { it.contains("loadBranches failed") })
    }

    @Test
    fun `disposed combo is not modified but completes loading`() {
        val combo = displayableCombo(displayable = false)
        var ends = 0
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "dev"),
            branchLoads { listOf("main") }, createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { _, _ ->
                ends++
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("disposed combo load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals(1, ends)
        assertFalse(combo.isEnabled)
        assertEquals(listOf(LOADING_BRANCH), (0 until combo.itemCount).map(combo::getItemAt))
    }

    @Test
    fun `superseded branch load cancels git and cannot overwrite latest result`() {
        val combo = displayableCombo()
        val firstStarted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val openCount = AtomicInteger(0)
        val finished = CountDownLatch(2)
        var startCount = 0
        var endCount = 0
        val supersededFlags = mutableListOf<Boolean>()
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            if (openCount.getAndIncrement() == 0) {
                branchOperation(
                    load = {
                        firstStarted.countDown()
                        while (!firstCancelled.get()) Thread.sleep(10)
                        throw CancellationException("superseded")
                    },
                    onCancel = { firstCancelled.set(true) },
                )
            } else {
                branchOperation { listOf("main", "latest") }
            }
        }

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "old"), coordinator, createStringAppender {},
            onLoadStart = { startCount++ },
            onLoadEnd = { _, superseded -> supersededFlags += superseded; endCount++; finished.countDown() },
            scheduleUi = { it() },
        )
        assertTrue("first discovery should start", firstStarted.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "latest"), coordinator, createStringAppender {},
            onLoadStart = { startCount++ },
            onLoadEnd = { _, superseded -> supersededFlags += superseded; endCount++; finished.countDown() },
            scheduleUi = { it() },
        )

        // The superseded load still signals its lifecycle end so the caller's in-flight
        // counter balances (start == end), but it is flagged superseded so callers skip
        // the retry-state reset for it.
        assertTrue("both branch loads should signal completion", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals("every start must have a matching end", 2, startCount)
        assertEquals("every end must pair with a start", 2, endCount)
        assertTrue("superseded load must be flagged so callers skip the retry reset", supersededFlags.any { it })
        assertTrue("latest load must not be flagged as superseded", supersededFlags.any { !it })
        assertTrue("superseded Git session should be cancelled", firstCancelled.get())
        assertEquals(listOf("main", "latest"), (0 until combo.itemCount).map(combo::getItemAt))
        assertEquals("latest", combo.selectedItem)
    }

    /**
     * The application shutdown window: `invokeLater` throws, so the schedule that would
     * have run `updateUi` never lands and its `finally` never balances the caller's
     * in-flight count. Dropping the signal leaves that editor disabled for good, so it
     * must be delivered anyway -- and on the EDT, because the callback touches Swing.
     */
    @Test
    fun `a failed UI schedule still ends the load exactly once on the EDT`() {
        val combo = displayableCombo()
        val ends = AtomicInteger()
        val onEdt = AtomicBoolean(true)
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, ComboBranchTarget(File("."), "dev"),
            branchLoads { listOf("main", "dev") }, createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { _, _ ->
                if (!EventQueue.isDispatchThread()) onEdt.set(false)
                ends.incrementAndGet()
                finished.countDown()
            },
            scheduleUi = { throw IllegalStateException("application is being disposed") },
        )

        assertTrue(
            "the end signal must survive a schedule that refused it",
            finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS),
        )
        assertEquals("the caller's counter must be balanced exactly once", 1, ends.get())
        assertTrue("the callback touches Swing, so it must not run on the loading thread", onEdt.get())
    }

    /**
     * The refresh half of the same shutdown window. `refreshComboBranches` is the path the
     * original defect was worst on: it posted the end signal through a helper that only logged
     * when the UI queue refused, so the callback was dropped outright — and because `loadEnded`
     * had already flipped, it never fired again, leaving the caller's in-flight count raised for
     * the rest of the session.
     */
    @Test
    fun `a failed UI schedule still ends a refresh exactly once on the EDT`() {
        val combo = displayableCombo(displayable = true)
        val ends = AtomicInteger()
        val onEdt = AtomicBoolean(true)
        val finished = CountDownLatch(1)

        refreshComboBranches(
            combo,
            File("."),
            "dev",
            refreshLoads(),
            createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { _, _ ->
                if (!EventQueue.isDispatchThread()) onEdt.set(false)
                ends.incrementAndGet()
                finished.countDown()
            },
            scheduleUi = { throw IllegalStateException("application is being disposed") },
            submodule = SubmoduleSource(File("."), "SubA", "https://example.com/repo.git"),
        )

        assertTrue(
            "the end signal must survive a schedule that refused it",
            finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS),
        )
        assertEquals("the caller's counter must be balanced exactly once", 1, ends.get())
        assertTrue("the callback touches Swing, so it must not run on the loading thread", onEdt.get())
    }

    private fun displayableCombo(displayable: Boolean = true): JComboBox<String> =
        object : JComboBox<String>() {
            override fun isDisplayable(): Boolean = displayable
        }

    private fun branchLoads(load: () -> List<String>): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            branchOperation(load = load)
        }

    /** A load coordinator whose workflow session lists one branch, for the refresh path. */
    private fun refreshLoads(): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            branchOperation(load = { listOf("main", "dev") })
        }

    private fun branchOperation(
        onCancel: () -> Unit = {},
        load: () -> List<String>,
    ): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listAllBranches" -> load()
                "cancel" -> onCancel()
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
