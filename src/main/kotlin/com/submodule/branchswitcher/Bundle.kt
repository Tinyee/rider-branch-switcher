package com.submodule.branchswitcher

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat

/**
 * I18n accessor using [DynamicBundle.getResourceBundle] and
 * [DynamicBundle.getLocale] — the public API that follows the IDE language.
 *
 * Usage: Bundle.msg("action.switch")  →  locale-dependent message
 */
object Bundle {

    private const val PATH = "messages.BranchSwitcherBundle"

    private val bundle by lazy {
        DynamicBundle.getResourceBundle(javaClass.classLoader, PATH)
    }

    @Nls
    fun msg(@PropertyKey(resourceBundle = PATH) key: String, vararg params: Any): String {
        val template = try {
            bundle.getString(key)
        } catch (_: Exception) {
            key
        }
        return if (params.isEmpty()) template
        else MessageFormat.format(template, *params)
    }
}
