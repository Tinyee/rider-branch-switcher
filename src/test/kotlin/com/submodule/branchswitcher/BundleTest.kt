package com.submodule.branchswitcher

import org.junit.Assert.*
import org.junit.Test

/** Validates runtime bundle lookup, formatting, and localized values. */
class BundleTest {

    @Test
    fun `missing key returns the key itself as fallback`() {
        val result = Bundle.msg("nonexistent.key.xyz")
        assertEquals("nonexistent.key.xyz", result)
    }

    @Test
    fun `formatted message works with params`() {
        val result = Bundle.msg("notify.switch.complete.msg", "dev")
        // In default locale (could be en or zh), should contain "dev"
        assertTrue("Formatted message should contain param 'dev', got: $result", "dev" in result)
    }

    @Test
    fun `all localized values are non-empty`() {
        val locales = listOf("EN" to java.util.Locale.ENGLISH, "ZH" to java.util.Locale.forLanguageTag("zh"))
        for ((label, locale) in locales) {
            val bundle = java.util.ResourceBundle.getBundle("messages.BranchSwitcherBundle", locale)
            val keys = bundle.keys
            assertTrue("$label bundle should contain messages", keys.hasMoreElements())
            while (keys.hasMoreElements()) {
                val key = keys.nextElement()
                val value = bundle.getString(key)
                assertTrue("$label key '$key' should be non-empty", value.trim().isNotEmpty())
            }
        }
    }
}
