package com.submodule.branchswitcher.ui

/** Session-scoped cache of `git ls-remote --heads` results, keyed by repository URL. */
internal class RemoteBranchCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private data class Entry(val names: List<String>, val storedAt: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun get(url: String): List<String>? {
        val entry = entries[url] ?: return null
        val age = System.currentTimeMillis() - entry.storedAt
        if (age >= ttlMillis) {
            entries.remove(url)
            return null
        }
        return entry.names
    }

    @Synchronized
    fun put(url: String, names: List<String>) {
        entries[url] = Entry(names, System.currentTimeMillis())
    }

    @Synchronized
    fun invalidate(url: String) {
        entries.remove(url)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}
