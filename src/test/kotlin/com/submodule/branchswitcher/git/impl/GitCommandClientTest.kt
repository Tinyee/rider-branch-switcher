package com.submodule.branchswitcher.git.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class GitCommandClientTest {
    @Test
    fun `parseRemoteHeads extracts head names and skips junk`() {
        val stdout = "06b9ad8d8943ee5bf94ca09aaabdbbf63e03c3f3\trefs/heads/develop\n" +
            "deadbeef\trefs/heads/HEAD\n" +
            "not-a-tab-line\n" +
            "ffffffffffffffffffffffffffffffffffffffff\trefs/heads/feature/x/y\n"
        assertEquals(listOf("HEAD", "develop", "feature/x/y"), parseRemoteHeads(stdout))
    }
}
