package com.submodule.branchswitcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards how a plugin.xml `<action>` gets its label.
 *
 * `text` and `description` attributes are literal strings — the platform does not resolve them
 * against the resource bundle, it displays them verbatim. An action declared with
 * `text="action.foo.text"` therefore shows that string in the menu, which is what the Tools menu
 * entry did until this was fixed: it read `action.switch.preset.action`.
 *
 * The supported route is to omit both attributes and let the platform look up
 * `action.<full action id>.text` and `action.<full action id>.description`. Nothing in the build
 * checks that mapping, and the mistake is invisible to every other test here — `Bundle.msg`
 * resolves the very same key correctly, so the bundle looks fine while the menu is wrong.
 *
 * These run on a bare JVM: this module's tests have no sandbox IDE, so the action's resolved
 * presentation cannot be read from `ActionManager`. What is asserted is the contract the platform
 * depends on — that the keys matching each action's id exist in both locales, and that no action
 * takes the literal route instead.
 */
class ActionLabelTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    private fun bundle(locale: Locale) =
        ResourceBundle.getBundle("messages.BranchSwitcherBundle", locale)

    private fun keysInAndroidStyleOf(path: String): Set<String> =
        File(path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .map { it.substringBefore('=') }
            .toSet()

    /** Every `<action>` id declared in plugin.xml. */
    private fun declaredActionIds(): List<String> =
        Regex("""<action\s+id="([^"]+)"""")
            .findAll(pluginXml)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `there is at least one action to check`() {
        assertTrue("no <action> found in plugin.xml — this test would pass vacuously", declaredActionIds().isNotEmpty())
    }

    /**
     * The regression itself. A `text` attribute holding a bundle key puts the key in the menu, so
     * a key-shaped value there is always a mistake — the only way to get a localized label is to
     * leave the attribute off.
     */
    @Test
    fun `no action declares a bundle key as a literal text or description`() {
        val offenders = Regex("""<action\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(pluginXml)
            .flatMap { match ->
                val block = match.value
                listOf("text" to block, "description" to block).mapNotNull { (attr, b) ->
                    Regex("""$attr="([^"]+)"""").find(b)?.groupValues?.get(1)
                        ?.takeIf { it.contains('.') && !it.contains(' ') }
                        ?.let { "$attr=\"$it\"" }
                }
            }
            .toList()

        assertTrue(
            "An <action> attribute is a literal string and is displayed verbatim, so a bundle key " +
                "there appears in the menu as the key. Omit the attribute and let the platform " +
                "look up action.<id>.text. Offending: $offenders",
            offenders.isEmpty(),
        )
    }

    /** The keys the platform will look up must exist, in both locales. */
    @Test
    fun `every action has convention-named text and description keys in both locales`() {
        for (locale in listOf("EN" to Locale.ROOT, "ZH" to Locale.forLanguageTag("zh"))) {
            val (label, tag) = locale
            val bundle = bundle(tag)
            for (id in declaredActionIds()) {
                for (suffix in listOf("text", "description")) {
                    val key = "action.$id.$suffix"
                    val value = try {
                        bundle.getString(key)
                    } catch (_: java.util.MissingResourceException) {
                        null
                    }
                    assertTrue(
                        "$label bundle has no '$key'. Without it the action falls back to " +
                            "showing its raw id where a label should be.",
                        value != null,
                    )
                    assertFalse("$label key '$key' is blank", value!!.isBlank())
                }
            }
        }
    }

    /**
     * The old key names are gone. If one is left behind, the action keeps working but the file
     * carries a dead entry that looks authoritative.
     */
    @Test
    fun `the retired key names are not left behind`() {
        val retired = setOf("action.switch.preset.action", "action.switch.preset.action.desc")
        val english = keysInAndroidStyleOf("src/main/resources/messages/BranchSwitcherBundle.properties")
        val chinese = keysInAndroidStyleOf("src/main/resources/messages/BranchSwitcherBundle_zh.properties")
        assertEquals("retired keys still in the English bundle: ${english intersect retired}", emptySet<String>(), english intersect retired)
        assertEquals("retired keys still in the Chinese bundle: ${chinese intersect retired}", emptySet<String>(), chinese intersect retired)
    }
}
