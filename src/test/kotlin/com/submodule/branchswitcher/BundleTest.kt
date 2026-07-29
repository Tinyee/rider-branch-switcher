package com.submodule.branchswitcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Validates i18n Bundle consistency: both locale files have identical key sets
 * and every key resolves to a non-empty value.
 */
class BundleTest {

    private val enProps = loadProps("BranchSwitcherBundle.properties")
    private val zhProps = loadProps("BranchSwitcherBundle_zh.properties")

    @Test
    fun `all Bundle keys resolve to non-empty string in default locale`() {
        for (key in enProps.stringPropertyNames()) {
            val value = Bundle.msg(key)
            assertTrue("Key '$key' should resolve to non-empty string, got: '$value'", value.isNotEmpty())
            assertNotEquals("Key '$key' should not fall back to key itself", key, value)
        }
    }

    @Test
    fun `English and Chinese properties have identical key sets`() {
        val enKeys = enProps.stringPropertyNames()
        val zhKeys = zhProps.stringPropertyNames()
        val enOnly = enKeys - zhKeys
        val zhOnly = zhKeys - enKeys
        assertTrue("Keys only in EN: $enOnly", enOnly.isEmpty())
        assertTrue("Keys only in ZH: $zhOnly", zhOnly.isEmpty())
    }

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
            var count = 0
            while (keys.hasMoreElements()) {
                val key = keys.nextElement()
                val value = bundle.getString(key)
                assertTrue("$label key '$key' should be non-empty", value.trim().isNotEmpty())
                count++
            }
            assertTrue("$label should have at least 50 keys, got $count", count >= 50)
        }
    }

    // ---- helpers ----

    private fun loadProps(name: String): Properties {
        val file = findPropertiesFile(name)
        assertNotNull("Properties file not found: $name", file)
        val props = Properties()
        file!!.reader().use { props.load(it) }
        return props
    }

    private fun findPropertiesFile(name: String): File? {
        // Search in common build output locations
        val candidates = listOf(
            File("build/resources/main/messages/$name"),
            File("src/main/resources/messages/$name"),
            File("../src/main/resources/messages/$name"),
        )
        for (c in candidates) {
            if (c.exists()) return c
        }
        // Fallback: search relative to project root
        val root = File(".").absoluteFile
        val fromRoot = File(root, "src/main/resources/messages/$name")
        return if (fromRoot.exists()) fromRoot else null
    }
}
