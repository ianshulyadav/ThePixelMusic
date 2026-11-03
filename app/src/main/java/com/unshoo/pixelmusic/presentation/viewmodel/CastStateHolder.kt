package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.unshoo.pixelmusic.data.service.cast.CastController
import com.unshoo.pixelmusic.data.service.cast.CastMirror
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @Deprecated shim. The cast implementation this class fronted is gone.
 *
 * Casting now lives entirely in `data/service/cast`: [CastController] owns routes
 * and the session, `CastPlayback` owns what plays on the receiver, and
 * `CastSessionPlayer` is the player the `MediaSession` hands out, so the session
 * itself already reports the receiver's state to every surface — the app, the
 * notification, Android Auto and the widgets.
 *
 * What is left here is only what the *legacy* code paths in `PlayerViewModel`
 * and `PlaybackStateHolder` still name: an always-null `castSession`, a null
 * `castPlayer`, and the three live flows the UI reads
 * ([isRemotePlaybackActive], [isCastConnecting], [remotePosition]) which are
 * mirrors of the real thing.
 *
 * Those legacy branches cannot run — `castSession` is never non-null, so every
 * `if (castSession != null && ...)` guard is dead — and deleting the ~30 of them
 * is a mechanical follow-up. Keeping this class until then is what stops this
 * patch from being a blind rewrite of two 5000-line files: `PlaybackStateHolder`
 * needs no edit at all, and `PlayerViewModel` loses only its transfer logic.
 */
@Deprecated("Casting is owned by data/service/cast; delete the remaining legacy branches and this class.")
@Singleton
class CastStateHolder @Inject constructor() {

    /** Never non-null. The real session lives in [CastController]. */
    val castSession: StateFlow<CastSession?> = MutableStateFlow<CastSession?>(null).asStateFlow()

    /** Never non-null. What plays on the receiver is `CastPlayback`. */
    val castPlayer: LegacyCastPlayer? = null

    /** Never non-null: nothing outside the service talks to a Cast session any more. */
    val sessionManager: SessionManager? = null

    val isRemotePlaybackActive: StateFlow<Boolean> = CastMirror.active

    val isCastConnecting: StateFlow<Boolean> = CastMirror.connecting

    val remotePosition: StateFlow<Long> = CastMirror.positionMs

    private val _remotelySeeking = MutableStateFlow(false)
    val isRemotelySeeking: StateFlow<Boolean> = _remotelySeeking.asStateFlow()

    private val _routeVolume = MutableStateFlow(100)
    val routeVolume: StateFlow<Int> = _routeVolume.asStateFlow()

    val isRefreshingRoutes: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    val castRoutes: StateFlow<List<MediaRouter.RouteInfo>> =
        MutableStateFlow<List<MediaRouter.RouteInfo>>(emptyList()).asStateFlow()

    val selectedRoute: StateFlow<MediaRouter.RouteInfo?> =
        MutableStateFlow<MediaRouter.RouteInfo?>(null).asStateFlow()

    fun startDiscovery() = Unit

    fun stopDiscovery() = Unit

    fun setCastPlayer(player: LegacyCastPlayer?) = Unit

    fun setRemotePlaybackActive(active: Boolean) = Unit

    fun setRemotelySeeking(seeking: Boolean) {
        _remotelySeeking.value = seeking
    }

    fun setRemotePosition(positionMs: Long) = Unit

    fun setCastConnecting(connecting: Boolean) = Unit

    fun setPendingCastRouteId(routeId: String?) = Unit

    fun setRouteVolume(volume: Int) {
        _routeVolume.value = volume
    }

    fun onCleared() {
        _remotelySeeking.value = false
    }
}

/**
 * The shape the legacy call sites expect of `CastPlayer` — which no longer
 * exists. Nothing ever returns an instance, so these are never called; they are
 * here so the dead branches around them still compile.
 */
@Deprecated("Use CastPlayback through CastSessionPlayer.")
interface LegacyCastPlayer {
    fun play()
    fun pause()
    fun seek(position: Long)
    fun next()
    fun previous()
    fun jumpToItem(itemId: Int, position: Long)
    fun setRepeatMode(repeatMode: Int)
}
