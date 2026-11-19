package spw.cue

import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Process-local contract shared by the two isolated Workshop plugin loaders. */
internal class CueDiscLink : AutoCloseable {
    private val owner = UUID.randomUUID()
    private var published: String? = null
    private var closed = false

    @Synchronized
    fun publish(path: String?, startFrame: Long?) {
        if (closed) return
        if (path.isNullOrBlank() || startFrame == null || startFrame < 0) {
            clear()
            return
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val value = "${HexFormat.of().formatHex(digest)}:$startFrame:$owner"
        if (value == published) return
        try {
            System.setProperty(KEY, value)
            published = value
        } catch (_: SecurityException) {
            // Playback and labels remain usable if this optional link is unavailable.
        }
    }

    private fun clear() {
        published?.let { value ->
            try {
                System.getProperties().remove(KEY, value)
            } catch (_: SecurityException) {
                // The runtime does not allow the optional link.
            }
        }
        published = null
    }

    @Synchronized
    override fun close() {
        closed = true
        clear()
    }

    companion object {
        internal const val KEY = "spw.cue.support.playback.segment.v1"
    }
}
