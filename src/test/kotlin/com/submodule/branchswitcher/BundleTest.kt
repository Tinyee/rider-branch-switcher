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
    fun `stash backup notice makes the count operation scoped`() {
        val english = java.util.ResourceBundle.getBundle(
            "messages.BranchSwitcherBundle",
            java.util.Locale.ROOT,
        ).getString("notify.switch.stash.backups.retained")
        val chinese = java.util.ResourceBundle.getBundle(
            "messages.BranchSwitcherBundle",
            java.util.Locale.forLanguageTag("zh"),
        ).getString("notify.switch.stash.backups.retained")

        assertTrue(
            "English notice must say the count belongs to this operation, got: $english",
            english.contains("This operation"),
        )
        assertTrue(
            "中文通知必须说明计数属于本次操作，实际值：$chinese",
            chinese.contains("本次操作"),
        )
    }

    @Test
    fun `all localized values are non-empty`() {
        val locales = listOf("EN" to java.util.Locale.ROOT, "ZH" to java.util.Locale.forLanguageTag("zh"))
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
