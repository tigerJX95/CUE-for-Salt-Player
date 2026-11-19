package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class PlaybackProbe : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        mediaChanged?.invoke()
        return null
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Ended && !runCatching { queuePlaybackOwnsEnd?.invoke() == true }.getOrDefault(false)) {
            runCatching { CueAlbumSequence.ended(lastPosition) }
        }
        if (state == PlaybackExtensionPoint.State.Ready || state == PlaybackExtensionPoint.State.Idle) {
            mediaChanged?.invoke()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) mediaChanged?.invoke()
    }

    override fun onSeekTo(position: Long) {
        lastSeek = Sample(position, System.nanoTime())
    }

    override fun onPositionUpdated(position: Long) {
        lastPosition = Sample(position, System.nanoTime())
    }

    companion object {
        @Volatile internal var queuePlaybackOwnsEnd: (() -> Boolean)? = null
        @Volatile internal var playing: Boolean? = null
        @Volatile internal var mediaChanged: (() -> Unit)? = null
        @Volatile internal var lastSeek: Sample? = null
            private set
        @Volatile internal var lastPosition: Sample? = null
            private set
    }

    internal data class Sample(val position: Long, val observedAt: Long)
}
