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
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JTextField

class BranchComboUtilTest {

    @Test
    fun `branch popup filter is case insensitive and preserves typed text`() {
        val combo = JComboBox(arrayOf("main", "feature/login", "Feature/Search"))
        combo.isEditable = true
        combo.putClientProperty(KEY_ALL_BRANCHES, listOf("main", "feature/login", "Feature/Search"))
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
        combo.putClientProperty(KEY_ALL_BRANCHES, listOf("main", "dev", "feature/login"))
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
            combo, File("."), "dev",
            branchLoads { listOf("main", "dev") }, createStringAppender {},
            onLoadStart = { starts++ },
            onLoadEnd = {
                ends++
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("branch load should finish", finished.await(5, TimeUnit.SECONDS))
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
        val finished = CountDownLatch(1)

        loadComboBranches(
            combo, File("."), "dev",
            branchLoads { error("broken") }, createStringAppender { logs += it },
            onLoadStart = {},
            onLoadEnd = {
                ends++
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("failed branch load should finish", finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, ends)
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
            combo, File("."), "dev",
            branchLoads { listOf("main") }, createStringAppender {},
            onLoadStart = {},
            onLoadEnd = {
                ends++
                finished.countDown()
            },
            scheduleUi = { it() },
        )

        assertTrue("disposed combo load should finish", finished.await(5, TimeUnit.SECONDS))
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
            combo, File("."), "old", coordinator, createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { finished.countDown() },
            scheduleUi = { it() },
        )
        assertTrue("first discovery should start", firstStarted.await(5, TimeUnit.SECONDS))

        loadComboBranches(
            combo, File("."), "latest", coordinator, createStringAppender {},
            onLoadStart = {},
            onLoadEnd = { finished.countDown() },
            scheduleUi = { it() },
        )

        assertTrue("both branch loads should finish", finished.await(5, TimeUnit.SECONDS))
        assertTrue("superseded Git session should be cancelled", firstCancelled.get())
        assertEquals(listOf("main", "latest"), (0 until combo.itemCount).map(combo::getItemAt))
        assertEquals("latest", combo.selectedItem)
    }

    private fun displayableCombo(displayable: Boolean = true): JComboBox<String> =
        object : JComboBox<String>() {
            override fun isDisplayable(): Boolean = displayable
        }

    private fun branchLoads(load: () -> List<String>): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            branchOperation(load = load)
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
