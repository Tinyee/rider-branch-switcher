package com.submodule.branchswitcher.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitStatusParserTest {
    @Test
    fun `status parser reads branch head and dirty record count`() {
        val inspection = parsePorcelainV2Status(
            """
            # branch.oid abc123
            # branch.head feature/work
            1 .M N... 100644 100644 100644 abc abc file.txt
            ? untracked.txt
            """.trimIndent(),
        )

        assertTrue(inspection.isGitRepository)
        assertEquals("feature/work", inspection.currentBranch)
        assertEquals("abc123", inspection.head)
        assertEquals(2, inspection.dirtyFileCount)
    }

    @Test
    fun `status parser represents detached and unborn heads without sentinel strings`() {
        val detached = parsePorcelainV2Status("# branch.oid abc123\n# branch.head (detached)")
        val unborn = parsePorcelainV2Status("# branch.oid (initial)\n# branch.head main")

        assertNull(detached.currentBranch)
        assertEquals("abc123", detached.head)
        assertEquals("main", unborn.currentBranch)
        assertNull(unborn.head)
    }
}
