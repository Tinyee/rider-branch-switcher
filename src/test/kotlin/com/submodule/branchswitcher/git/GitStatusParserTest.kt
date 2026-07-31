package com.submodule.branchswitcher.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitStatusParserTest {
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
