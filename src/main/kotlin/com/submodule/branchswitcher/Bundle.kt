package com.submodule.branchswitcher

import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * I18n resource bundle accessor.
 * Uses standard Java ResourceBundle — IntelliJ discovers locale-specific
 * .properties files automatically via the <resource-bundle> in plugin.xml.
 *
 * Usage: Bundle.message("action.switch")  →  "切到此预设" (zh) or "Switch to this Preset" (en)
 *
 * Properties files: src/main/resources/messages/BranchSwitcherBundle*.properties
 */
object Bundle {
    private const val BASE_NAME = "messages.BranchSwitcherBundle"

    private val bundle: ResourceBundle by lazy {
        val locale = Locale.getDefault()
        try {
            ResourceBundle.getBundle(BASE_NAME, locale)
        } catch (_: MissingResourceException) {
            ResourceBundle.getBundle(BASE_NAME, Locale.ENGLISH)
        }
    }

    fun message(key: String, vararg params: Any): String {
        val template = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            key
        }
        return if (params.isEmpty()) template
        else String.format(template, *params)
    }
}
