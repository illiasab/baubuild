package com.vesper.baubuild.core.media

/**
 * Small in-memory cache of encoded image bytes keyed by URL, used by
 * [com.vesper.baubuild.designsystem.UserAvatar]. After an avatar upload the picked bytes are stored under the
 * returned URL so the new photo renders immediately (and `memory://` URLs render at all)
 * without an image-loading library. Accessed from the main thread.
 */
object LocalImageStore {
    private const val MAX_ENTRIES = 16
    private val entries = LinkedHashMap<String, ByteArray>()

    fun put(url: String, bytes: ByteArray) {
        entries.remove(url)
        entries[url] = bytes
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    fun get(url: String): ByteArray? = entries[url]

    fun remove(url: String) {
        entries.remove(url)
    }
}
