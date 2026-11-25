package spw.cue

// The playback cover lock serializes native notifications and completed artwork reads.
internal class PlaybackCoverTransition<T : Any> {
    private data class Resolved<T>(val item: Any, val cover: T?)

    private var resolved: Resolved<T>? = null
    private var deferredClear = false

    fun interceptMissing(item: Any?, canRead: Boolean, publish: (T) -> Unit): Boolean {
        if (item == null || !canRead) {
            nativePublished()
            return false
        }
        val result = resolved?.takeIf { it.item === item }
        if (result == null) {
            deferredClear = true
            return true
        }
        deferredClear = false
        val image = result.cover ?: return false
        publish(image)
        return true
    }

    fun complete(
        item: Any, current: Any?, cover: T?, nativeHasCover: Boolean,
        replaceFallback: Boolean = false, publish: (T?) -> Unit
    ): Boolean {
        if (current !== item) return false
        resolved = Resolved(item, cover)
        val shouldPublish = !nativeHasCover && (cover != null || deferredClear || replaceFallback)
        deferredClear = false
        if (shouldPublish) publish(cover)
        return true
    }

    fun nativePublished() {
        deferredClear = false
    }

    fun restoreAfterNativeClear(item: Any?, publish: (T) -> Unit): Boolean {
        val image = resolved?.takeIf { it.item === item }?.cover ?: return false
        deferredClear = false
        publish(image)
        return true
    }

    fun reset(): Boolean {
        val clear = deferredClear
        resolved = null
        deferredClear = false
        return clear
    }
}
