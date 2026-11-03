package com.unshoo.pixelmusic.data.service.cast

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.cast.framework.CastSession
import com.unshoo.pixelmusic.data.service.http.CastSessionSecurity
import com.unshoo.pixelmusic.data.service.http.MediaFileHttpServerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves this phone's own audio files to a receiver.
 *
 * A Chromecast cannot read a `content://` or `file://` URI: it is another
 * machine on the network. So local tracks are handed over the LAN by
 * [MediaFileHttpServerService], with a per-session token and an allow-list of
 * the song ids that may be fetched.
 *
 * The allow-list is the part the old implementation got wrong: it was fixed once
 * when the transfer started, so anything the auto-queue appended afterwards —
 * every auto-queue refill — came back 403 and stopped playback on the receiver.
 * Here the list is widened on every resolve, so a queue that grows while casting
 * keeps working.
 */
object LanCastSource {
    private const val TAG = "PixelMusicCast"
    private val startMutex = Mutex()

    /** Ids this session is allowed to fetch. Grows as the queue does. */
    private val allowedSongIds = LinkedHashSet<String>()

    fun deviceAddressOf(session: CastSession?): String? =
        runCatching { session?.castDevice?.inetAddress?.hostAddress }.getOrNull()?.trim()?.ifEmpty { null }

    /**
     * Brings the LAN server up if it is not already, and returns its base
     * address (`http://192.168.x.y:port`). Suspending: a release build can take
     * a moment to promote the service; [CastPlayback] resolves off the main
     * thread anyway.
     */
    suspend fun ensureRunning(
        context: Context,
        session: CastSession?,
        songIds: Collection<String>,
    ): String? = startMutex.withLock {
        val hint = deviceAddressOf(session)
        synchronized(allowedSongIds) {
            allowedSongIds.clear()
            allowedSongIds.addAll(songIds)
        }
        MediaFileHttpServerService.configureCastSessionAccess(
            allowedSongIds = synchronized(allowedSongIds) { allowedSongIds.toSet() },
            castDeviceIpHint = hint,
        )

        MediaFileHttpServerService.serverAddress
            ?.takeIf { MediaFileHttpServerService.isServerRunning }
            ?.let { return@withLock it }

        MediaFileHttpServerService.lastFailureReason = null
        MediaFileHttpServerService.lastFailureMessage = null
        val intent = Intent(context, MediaFileHttpServerService::class.java).apply {
            action = MediaFileHttpServerService.ACTION_START_SERVER
            hint?.let { putExtra(MediaFileHttpServerService.EXTRA_CAST_DEVICE_IP, it) }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not start the LAN media server", e)
            return@withLock null
        }

        repeat(START_POLL_TRIES) {
            if (MediaFileHttpServerService.isServerRunning) {
                MediaFileHttpServerService.serverAddress?.let { return@withLock it }
            }
            if (MediaFileHttpServerService.lastFailureReason != null) {
                Log.w(
                    TAG,
                    "LAN media server failed: ${MediaFileHttpServerService.lastFailureReason} (${MediaFileHttpServerService.lastFailureMessage})"
                )
                return@withLock null
            }
            delay(START_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "timed out waiting for the LAN media server")
        null
    }

    /**
     * A URL the receiver can open for [songId]. [serverAddress] comes from
     * [ensureRunning]; [songIds] is the queue as it stands now, so a track the
     * auto-queue has just appended is admitted before its URL is handed over.
     */
    fun urlFor(
        serverAddress: String,
        songId: String,
        songIds: Collection<String>,
        session: CastSession?,
    ): String? {
        val token = synchronized(allowedSongIds) {
            if (allowedSongIds.add(songId) || songIds.isNotEmpty()) {
                allowedSongIds.addAll(songIds)
            }
            MediaFileHttpServerService.configureCastSessionAccess(
                allowedSongIds = allowedSongIds.toSet(),
                castDeviceIpHint = deviceAddressOf(session),
            ).authToken
        }
        return CastSessionSecurity.buildSongUrl(
            serverAddress = serverAddress,
            songId = songId,
            streamRevision = "1",
            authToken = token,
        )
    }

    fun stop(context: Context) {
        synchronized(allowedSongIds) { allowedSongIds.clear() }
        runCatching {
            context.stopService(Intent(context, MediaFileHttpServerService::class.java))
        }
    }

    private const val START_POLL_TRIES = 200
    private const val START_POLL_INTERVAL_MS = 100L
}
