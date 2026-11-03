package com.unshoo.pixelmusic.data.service.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Something other than this phone playing the phone's queue: a Cast receiver.
 *
 * The phone's player stays the owner of the *queue* — every row, every
 * reorder, every autoplay refill keeps reading it exactly as before — and is
 * held paused while the receiver is the speaker. The receiver owns the *clock*:
 * [positionMs], [isPlaying] and the rest come from it, and
 * [CastSessionPlayer] reports them in place of the paused local ones so the
 * app, the notification, Android Auto and the widgets all show what is really
 * playing.
 *
 * Two things keep the halves in step:
 *
 *  - the phone moving to another track (a skip, a tap in the queue, an autoplay
 *    refill) loads that track on the receiver, and
 *  - the receiver moving to another track (it finished one, or someone used its
 *    own remote) moves the phone's player to the same one.
 *
 * Everything here runs on the main thread.
 */
interface RemoteSpeaker {
    var sink: CastSink?

    /** Whether this receiver is the speaker. Everything else is meaningful only while it is. */
    val active: Boolean
    val playWhenReady: Boolean
    val isPlaying: Boolean

    /** In [Player]'s own terms. */
    val playbackState: Int
    val positionMs: Long

    /** [C.TIME_UNSET] when the receiver does not know yet. */
    val durationMs: Long

    /** 0..100. */
    val deviceVolume: Int

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 0..1 of the receiver's own range. */
    fun setVolume(fraction: Float)

    /** A new track became the phone's current one. */
    fun onLocalTransition(item: MediaItem?, reason: Int)

    /** The phone's queue changed under the playing track. */
    fun onLocalQueueChanged()
    fun onLocalRepeatChanged(repeatMode: Int)

    /** Something started the phone's own (paused) player while this is the speaker. */
    fun onLocalStartedPlaying(player: Player)
}

/** Told when the receiver changes something the session player reports as its own. */
interface CastSink {
    /** Play state, buffering, or the playhead moved. */
    fun onRemotePlaybackChanged()

    /** The music moved to a receiver, or back. */
    fun onRemoteRouteChanged()

    /** The receiver's volume changed, from here or from its own remote. */
    fun onRemoteVolumeChanged()
}

/** A receiver that never is one, for when the feature is unavailable. */
object NoSpeaker : RemoteSpeaker {
    override var sink: CastSink? = null
    override val active = false
    override val playWhenReady = false
    override val isPlaying = false
    override val playbackState = Player.STATE_IDLE
    override val positionMs = 0L
    override val durationMs = C.TIME_UNSET
    override val deviceVolume = 0
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setVolume(fraction: Float) = Unit
    override fun onLocalTransition(item: MediaItem?, reason: Int) = Unit
    override fun onLocalQueueChanged() = Unit
    override fun onLocalRepeatChanged(repeatMode: Int) = Unit
    override fun onLocalStartedPlaying(player: Player) = Unit
}
