package com.submodule.branchswitcher.switch

/**
 * Validates the branch shorthand accepted by `git check-ref-format --branch`.
 * This intentionally fails closed before starting a multi-repository operation.
 */
fun isValidBranchName(name: String): Boolean {
    if (name.isEmpty() || name != name.trim()) return false
    if (name == "@" || name.startsWith("-")) return false
    if (name.startsWith("/") || name.endsWith("/")) return false
    if (name.contains("//")) return false
    if (name.contains("..") || name.contains("@{")) return false
    if (name.any { it.code <= 32 || it.code == 127 || it in "~^:?*[\\\\" }) return false

    return name.split('/').none { component ->
        component.startsWith(".") ||
            component.endsWith(".") ||
            component.endsWith(".lock")
    }
}
