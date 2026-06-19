package com.submodule.branchswitcher.switch

/**
 * Platform-agnostic progress display handle for switch pipeline steps.
 * Platform modules adapt their progress APIs (e.g. IDE progress indicator) to this interface.
 */
interface ProgressHandle {
    var fraction: Double
    var text: String?
    var text2: String?
    var isIndeterminate: Boolean
}
