package com.submodule.branchswitcher.git.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `submodule-only porcelain status is recognized`() {
        // Submodule worktree mismatch: <sub> field starts with S.
        val worktree = "# branch.oid abc123\n# branch.head main\n" +
            "1 .M S..U 160000 160000 160000 oid1 oid1 Sub"
        assertTrue(isSubmoduleOnlyPorcelainStatus(worktree))

        // A staged gitlink change is a superproject index change and must be
        // protected by stash; it is not submodule-only dirt.
        val staged = "# branch.oid abc123\n1 M. S... 160000 160000 160000 oid1 oid2 Sub"
        assertFalse(isSubmoduleOnlyPorcelainStatus(staged))
    }

    @Test
    fun `regular file change is not submodule-only`() {
        // <sub> field starts with N for non-submodules.
        val output = "# branch.oid abc123\n1 .M N... 100644 100644 100644 oid1 oid1 file.txt"
        assertFalse(isSubmoduleOnlyPorcelainStatus(output))
    }

    @Test
    fun `untracked entry is not submodule-only`() {
        assertFalse(isSubmoduleOnlyPorcelainStatus("# branch.oid abc123\n? untracked.txt"))
    }

    @Test
    fun `unmerged entry is not submodule-only`() {
        val output = "# branch.oid abc123\n" +
            "u UU N... 100644 100644 100644 100644 oid1 oid2 oid3 file.txt"
        assertFalse(isSubmoduleOnlyPorcelainStatus(output))
    }

    @Test
    fun `mixed submodule and regular dirt is not submodule-only`() {
        val output = "# branch.oid abc123\n" +
            "1 .M S..U 160000 160000 160000 oid1 oid1 Sub\n" +
            "1 .M N... 100644 100644 100644 oid2 oid2 file.txt"
        assertFalse(isSubmoduleOnlyPorcelainStatus(output))
    }

    @Test
    fun `clean or empty status is not submodule-only`() {
        assertFalse(isSubmoduleOnlyPorcelainStatus(""))
        assertFalse(isSubmoduleOnlyPorcelainStatus("# branch.oid abc123\n"))
    }
}
