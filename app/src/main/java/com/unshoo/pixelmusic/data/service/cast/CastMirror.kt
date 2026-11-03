package com.unshoo.pixelmusic.data.service.cast

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the casting half of the app looks like from the UI.
 *
 * [CastPlayback] lives inside `MusicService` and is driven by the receiver; the
 * Compose layer must not hold a `CastSession` of its own (that is what made the
 * old implementation fight itself: two places telling the receiver what to do).
 * So the service publishes the few facts the screen needs here, and the screen
 * only observes.
 *
 * Everything is written on the main thread by [CastPlayback] and [CastController].
 */
object CastMirror {

    private val _active = MutableStateFlow(false)

    /** A receiver is the speaker right now. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _connecting = MutableStateFlow(false)

    /** A receiver has been chosen and the session is being set up. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _playing = MutableStateFlow(false)

    /** The receiver is actually playing. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)

    /** The receiver's playhead. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(C.TIME_UNSET)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-shot text for the UI ("Can't cast this track", "Receiver unreachable"). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun publish(
        active: Boolean,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        playbackState: Int,
    ) {
        _active.value = active
        _playing.value = playing
        _positionMs.value = positionMs.coerceAtLeast(0L)
        _durationMs.value = durationMs
        _playbackState.value = playbackState
    }

    fun publishConnecting(connecting: Boolean) {
        _connecting.value = connecting
    }

    fun say(text: String?) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }
}
