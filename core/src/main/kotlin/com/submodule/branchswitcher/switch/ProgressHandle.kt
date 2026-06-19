package com.submodule.branchswitcher.switch

/**
 * Platform-agnostic progress display handle.
 * Replaces [com.intellij.openapi.progress.ProgressIndicator] for core-compatible switch steps.
 */
interface ProgressHandle {
    var fraction: Double
    var text: String?
    var text2: String?
    var isIndeterminate: Boolean
}
