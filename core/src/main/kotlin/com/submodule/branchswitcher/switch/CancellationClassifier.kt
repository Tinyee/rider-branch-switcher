package com.submodule.branchswitcher.switch

/**
 * Classifies exceptions as cancellation without importing platform types.
 *
 * The default implementation only recognizes [java.util.concurrent.CancellationException].
 * Platform modules should inject a classifier that also recognizes
 * [com.intellij.openapi.progress.ProcessCanceledException].
 */
fun interface CancellationClassifier {
    fun isCancellation(e: Throwable): Boolean

    companion object {
        /** Default: only JDK cancellation. Platform code should wire a richer classifier. */
        val DEFAULT: CancellationClassifier = object : CancellationClassifier {
            override fun isCancellation(e: Throwable) =
                e is java.util.concurrent.CancellationException
        }
    }
}

/** Convenience: re-throw if [classifier] says this is cancellation. */
fun CancellationClassifier.rethrowIfCancellation(e: Exception) {
    if (isCancellation(e)) throw e
}
