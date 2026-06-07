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
    fun `every key count matches between locales`() {
        assertEquals("Key count mismatch between EN and ZH", enProps.size, zhProps.size)
    }

    @Test
    fun `known keys resolve correctly`() {
        // Spot-check: plugin.title should resolve (exact value depends on locale)
        assertTrue(Bundle.msg("plugin.title").isNotEmpty())
        assertTrue(Bundle.msg("action.switch").isNotEmpty())
        // notify.switch.complete.msg has a {0} placeholder for the preset name
        assertTrue(Bundle.msg("notify.switch.complete.msg").contains("{0}"))
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
    fun `all Chinese values are non-empty`() {
        val locale = java.util.Locale("zh")
        val bundle = java.util.ResourceBundle.getBundle("messages.BranchSwitcherBundle", locale)
        val keys = bundle.keys
        var count = 0
        while (keys.hasMoreElements()) {
            val k = keys.nextElement()
            val v = bundle.getString(k)
            assertTrue("ZH key '$k' should be non-empty", v.trim().isNotEmpty())
            count++
        }
        assertTrue("Should have at least 50 keys, got $count", count >= 50)
    }

    @Test
    fun `all English values are non-empty`() {
        val locale = java.util.Locale.ENGLISH
        val bundle = java.util.ResourceBundle.getBundle("messages.BranchSwitcherBundle", locale)
        val keys = bundle.keys
        var count = 0
        while (keys.hasMoreElements()) {
            val k = keys.nextElement()
            val v = bundle.getString(k)
            assertTrue("EN key '$k' should be non-empty", v.trim().isNotEmpty())
            count++
        }
        assertTrue("Should have at least 50 keys, got $count", count >= 50)
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
