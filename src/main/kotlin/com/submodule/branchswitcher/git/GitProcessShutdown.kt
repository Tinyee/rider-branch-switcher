package com.submodule.branchswitcher.git

import com.intellij.openapi.Disposable

/**
 * Shuts down the shared Git process thread pools when the plugin is disposed,
 * releasing the plugin classloader once in-flight drains and watchers finish.
 *
 * Instantiated (and therefore disposed on unload) by [BranchSwitcherService]
 * whenever a Git operation session is created; the pools are lazy, so the
 * service is never instantiated unless the pools could exist.
 */
class GitProcessShutdown : Disposable {
    override fun dispose() {
        GitProcessResources.shutdown()
    }
}
