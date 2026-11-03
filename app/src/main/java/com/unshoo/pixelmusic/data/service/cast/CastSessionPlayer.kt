package com.unshoo.pixelmusic.data.service.cast

import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player `MusicService` hands its `MediaSession`.
 *
 * It *is* the phone's player — the queue, the timeline, the playlists and every
 * media item in them are the local ones, unchanged — except while a receiver is
 * the speaker, when the clock and the play state come from [CastPlayback]
 * instead. That is the whole trick, and it is why casting needs no second media
 * session, no second notification and no `CastMediaSessionPlayer` swapping
 * itself in and out: everything that reads the session (the app's controller,
 * the notification, the lock screen, Android Auto, the widgets and Wear) sees
 * one player whose clock happens to be the receiver's.
 *
 * Transport commands go to the receiver while it is the speaker and to the
 * local player otherwise. Queue edits always go to the local player, because the
 * local player stays the owner of the queue.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CastSessionPlayer(
    player: Player,
    private val cast: CastPlayback,
) : ForwardingPlayer(player), CastSink {

    /**
     * Listeners registered through this wrapper, kept so the receiver's changes
     * can be announced to them. The phone's own player never announces them — it
     * is paused, and as far as it knows nothing is happening.
     */
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    private var reportedPlayWhenReady = false
    private var reportedPlaybackState = Player.STATE_IDLE
    private var reportedIsPlaying = false

    /** Tells [cast] that the phone's queue moved, so the receiver's window can follow. */
    private val queueWatcher = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cast.onLocalTransition(mediaItem, reason)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            cast.onLocalQueueChanged()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            cast.onLocalRepeatChanged(repeatMode)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && cast.active) cast.onLocalStartedPlaying(wrappedPlayer)
        }
    }

    init {
        cast.sink = this
        wrappedPlayer.addListener(queueWatcher)
    }

    private companion object {
        val CAST_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()

        /** A hardware key press, as a share of the receiver's range. */
        const val VOLUME_STEP = 0.05f
    }

    override fun release() {
        runCatching { wrappedPlayer.removeListener(queueWatcher) }
        listeners.clear()
        super.release()
    }

    // ---- what the session sees ----------------------------------------------

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
        reportedPlayWhenReady = getPlayWhenReady()
        reportedPlaybackState = getPlaybackState()
        reportedIsPlaying = isPlaying()
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getPlayWhenReady(): Boolean =
        if (cast.active) cast.playWhenReady else super.getPlayWhenReady()

    override fun isPlaying(): Boolean =
        if (cast.active) cast.isPlaying else super.isPlaying()

    override fun getPlaybackState(): Int =
        if (cast.active) cast.playbackState else super.getPlaybackState()

    override fun getCurrentPosition(): Long =
        if (cast.active) cast.positionMs else super.getCurrentPosition()

    override fun getContentPosition(): Long =
        if (cast.active) cast.positionMs else super.getContentPosition()

    override fun getBufferedPosition(): Long =
        if (cast.active) cast.positionMs else super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long =
        if (cast.active) cast.positionMs else super.getContentBufferedPosition()

    override fun getDuration(): Long =
        if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getDuration()

    override fun getContentDuration(): Long =
        if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getContentDuration()

    override fun getDeviceInfo(): DeviceInfo =
        if (cast.active) CAST_DEVICE else super.getDeviceInfo()

    override fun getDeviceVolume(): Int =
        if (cast.active) cast.deviceVolume else super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean =
        if (cast.active) false else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int) {
        if (cast.active) cast.setVolume(volume / 100f) else super.setDeviceVolume(volume)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (cast.active) cast.setVolume(volume / 100f) else super.setDeviceVolume(volume, flags)
    }

    override fun setDeviceMuted(muted: Boolean) {
        if (!cast.active) super.setDeviceMuted(muted)
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (!cast.active) super.setDeviceMuted(muted, flags)
    }

    // ---- transport ----------------------------------------------------------

    override fun play() {
        if (cast.active) {
            cast.play()
            return
        }
        super.play()
    }

    override fun pause() {
        if (cast.active) {
            cast.pause()
            return
        }
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (cast.active) {
            if (playWhenReady) cast.play() else cast.pause()
            return
        }
        super.setPlayWhenReady(playWhenReady)
    }

    override fun seekTo(positionMs: Long) {
        if (cast.active) {
            cast.seekTo(positionMs)
            return
        }
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (cast.active && mediaItemIndex == wrappedPlayer.currentMediaItemIndex) {
            cast.seekTo(positionMs)
            return
        }
        // Moving to another row is a queue move: the phone's player owns the
        // queue, and [CastPlayback] loads whatever becomes current.
        super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        if (cast.active) {
            cast.seekTo((cast.positionMs - wrappedPlayer.seekBackIncrement).coerceAtLeast(0L))
            return
        }
        super.seekBack()
    }

    override fun seekForward() {
        if (cast.active) {
            val target = cast.positionMs + wrappedPlayer.seekForwardIncrement
            val duration = cast.durationMs
            cast.seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
            return
        }
        super.seekForward()
    }

    override fun seekToNext() {
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (cast.active) {
            castPrevious()
            return
        }
        super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (cast.active) {
            castPrevious()
            return
        }
        super.seekToPreviousMediaItem()
    }

    /** Back, on a receiver: restart past the first seconds, else the previous track. */
    private fun castPrevious() {
        if (cast.positionMs > wrappedPlayer.maxSeekToPreviousPosition || !wrappedPlayer.hasPreviousMediaItem()) {
            cast.seekTo(0L)
        } else {
            // The queue move happens here; [CastPlayback] loads whatever becomes
            // current onto the receiver.
            super.seekToPreviousMediaItem()
        }
    }

    override fun setRepeatMode(repeatMode: Int) {
        // The queue — and therefore repeat — belongs to the phone; the receiver
        // is told about it separately by [CastPlayback.onLocalRepeatChanged].
        super.setRepeatMode(repeatMode)
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    // ---- told by the receiver ----------------------------------------------

    override fun onRemotePlaybackChanged() {
        val playWhenReady = getPlayWhenReady()
        val state = getPlaybackState()
        val playing = isPlaying()

        if (playWhenReady != reportedPlayWhenReady) {
            reportedPlayWhenReady = playWhenReady
            listeners.forEach {
                runCatching {
                    it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                }
            }
        }
        if (state != reportedPlaybackState) {
            reportedPlaybackState = state
            listeners.forEach { runCatching { it.onPlaybackStateChanged(state) } }
        }
        if (playing != reportedIsPlaying) {
            reportedIsPlaying = playing
            listeners.forEach { runCatching { it.onIsPlayingChanged(playing) } }
        }

        // Always: a controller extrapolates the position from the last one it was
        // given, and has to be re-anchored whenever the receiver has been
        // seeked, stalled or resumed.
        val position = Player.PositionInfo(
            /* windowUid = */ null,
            /* mediaItemIndex = */ currentMediaItemIndex,
            /* mediaItem = */ currentMediaItem,
            /* periodUid = */ null,
            /* periodIndex = */ currentPeriodIndex,
            /* positionMs = */ currentPosition,
            /* contentPositionMs = */ contentPosition,
            /* adGroupIndex = */ C.INDEX_UNSET,
            /* adIndexInAdGroup = */ C.INDEX_UNSET,
        )
        listeners.forEach {
            runCatching {
                it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL)
            }
        }
    }

    override fun onRemoteRouteChanged() {
        val info = getDeviceInfo()
        listeners.forEach { runCatching { it.onDeviceInfoChanged(info) } }
        onRemoteVolumeChanged()
    }

    override fun onRemoteVolumeChanged() {
        val volume = getDeviceVolume()
        val muted = isDeviceMuted()
        listeners.forEach { runCatching { it.onDeviceVolumeChanged(volume, muted) } }
    }
}
