package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteBranchCacheTest {
    @Test
    fun `put then get returns the same list`() {
        val cache = RemoteBranchCache()
        cache.put("u", listOf("a", "b"))
        assertEquals(listOf("a", "b"), cache.get("u"))
    }

    @Test
    fun `expired entry returns null`() {
        val cache = RemoteBranchCache(ttlMillis = -1) // anything not past can't be negative-age; use 0
        // ttlMillis=0: stored immediately, age ~0 → treat as expired when age >= ttl
        cache.put("u", listOf("a"))
        assertNull(cache.get("u"))
    }

    @Test
    fun `invalidate removes the entry`() {
        val cache = RemoteBranchCache()
        cache.put("u", listOf("a"))
        cache.invalidate("u")
        assertNull(cache.get("u"))
    }
}
