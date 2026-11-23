package com.unshoo.pixelmusic.data.discord

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Shows "Listening to PixelMusic — <song> by <artist>" on the user's Discord profile through the
 * official Discord Social SDK (OAuth account linking, no user tokens scraped).
 *
 * Cost model: nothing is loaded until the user turns it on. When on, all SDK work runs on one
 * daemon thread, and the presence is only pushed on song change / play / pause / seek; Discord
 * renders the progress timer itself from the start/end timestamps.
 */
object DiscordPresence {

    const val APPLICATION_ID = 1574046094302773258L

    /** Rich Presence art asset key uploaded in the Discord Developer Portal. */
    private const val LOGO_ASSET = "pixelmusic_discord_1024"

    data class UiState(
        val enabled: Boolean = false,
        val linked: Boolean = false,
        val busy: Boolean = false,
        val status: String = "Not connected",
        val error: String? = null,
        /** Presence goes through the installed, signed-in Discord app (no OAuth login needed). */
        val viaApp: Boolean = false,
        val options: Options = Options(),
    )

    /** User choices from the Discord card's options dropdown (stored in plain prefs, not secret). */
    data class Options(
        /** Master switch: show the status at all (stays logged in when off). */
        val showActivity: Boolean = true,
        /** Friends' member list says "Listening to <song>" instead of "Listening to PixelMusic". */
        val songNameInList: Boolean = false,
        /** Keep a "Paused" status instead of clearing it when playback pauses. */
        val showWhilePaused: Boolean = true,
        /** Never show songs that only exist on this phone (privacy). */
        val hideLocalSongs: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PixelDiscord").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- everything below is confined to the PixelDiscord thread ----
    private var appContext: Context? = null
    private var prefsInstance: SharedPreferences? = null
    private var started = false
    private var awaitingAuth = false
    private var pumpJob: Job? = null
    private var latest: Snapshot? = null
    private var applied: Snapshot? = null
    private var publishJob: Job? = null

    private data class Snapshot(
        val playing: Boolean,
        /** YouTube video id for online songs (enables Join / Listen button); null for local files. */
        val videoId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String?,
        val startMs: Long,
        val endMs: Long,
    ) {
        /** Same song/state with timestamps within 2s → no need to re-send. */
        fun sameAs(other: Snapshot?): Boolean = other != null &&
            playing == other.playing && videoId == other.videoId &&
            title == other.title && artist == other.artist &&
            album == other.album && artUrl == other.artUrl &&
            kotlin.math.abs(startMs - other.startMs) < 2_000
    }

    // ------------------------------------------------------------------ public API (any thread)

    /** Call from MainActivity.onCreate. Starts the SDK only if the user enabled Discord before. */
    fun onActivityCreated(activity: Activity) {
        val ref = WeakReference(activity)
        scope.launch {
            appContext = activity.applicationContext
            // Cheap plain-prefs flag first: opening the encrypted store spins up the Keystore
            // and Tink, which measurably slowed cold start on low-end phones (Redmi 6A).
            if (started) runCatching { setSdkEngineActivity(activity) }
            _state.update { it.copy(options = loadOptions(activity.applicationContext)) }
            if (!isEnabledFlag(activity.applicationContext)) return@launch
            val prefs = prefs() ?: return@launch
            _state.update { it.copy(enabled = true, linked = prefs.hasTokens()) }
            ref.get()?.let { start(it) }
        }
    }

    /** "Connect Discord" button: opens the Discord app / browser to authorize PixelMusic. */
    fun connect(activity: Activity) {
        val ref = WeakReference(activity)
        scope.launch {
            appContext = activity.applicationContext
            val prefs = prefs() ?: return@launch
            setEnabledFlag(activity.applicationContext, true)
            _state.update { it.copy(enabled = true, error = null) }
            val act = ref.get() ?: return@launch
            if (!start(act)) return@launch
            if (prefs.hasTokens()) {
                connectWithSavedToken(prefs)
            } else {
                // Ignore repeat taps: a second Authorize replaces the pending request's OAuth
                // "state", so the first callback then fails with "invalid state".
                if (awaitingAuth) return@launch
                awaitingAuth = true
                // The SDK launches the auth screen from its engine Activity; make sure it is the
                // live one (a stale, destroyed Activity broke the redirect back).
                setSdkEngineActivity(act)
                setStatus("Approve PixelMusic in Discord…", busy = true)
                DiscordNative.nativeAuthorize()
            }
        }
    }

    /**
     * Fully detaches PixelMusic from Discord:
     *  1. clears the status shown on the user's profile and closes the SDK connection,
     *  2. revokes the OAuth token at Discord (if one was ever issued), so the authorization
     *     is gone server-side too,
     *  3. deletes every locally stored Discord value (tokens, enabled flag) from disk,
     *  4. destroys the native client and releases the Activity the SDK was holding.
     * Nothing survives; the next "Connect" starts from scratch.
     */
    fun disconnect() {
        scope.launch {
            val prefs = prefs()
            val access = prefs?.getString(KEY_ACCESS, null)
            val refresh = prefs?.getString(KEY_REFRESH, null)

            pumpJob?.cancel()
            pumpJob = null
            if (started) runCatching { DiscordNative.nativeShutdown() }
                .onFailure { Timber.tag(TAG).e(it, "Discord shutdown failed") }
            started = false
            awaitingAuth = false
            latest = null
            applied = null

            // Revoke server-side (public client: client_id + token, no secret).
            listOfNotNull(refresh, access).forEach { revokeToken(it) }

            prefs?.edit()?.clear()?.commit()
            prefsInstance = null
            appContext?.let { ctx ->
                runCatching { ctx.deleteSharedPreferences(PREFS_NAME) }
                runCatching { ctx.deleteSharedPreferences(PREFS_NAME_PLAIN) }
                runCatching { ctx.deleteSharedPreferences(PREFS_NAME_FLAGS) }
            }
            // NOTE: do not null the SDK's engine Activity here: its RPC client unbinds from the
            // Discord app asynchronously using it, and nulling it crashed the app on Disconnect.
            presenceVisible = false
            _state.value = UiState()
            Timber.tag(TAG).i("Discord fully disconnected; local auth deleted")
        }
    }

    private fun revokeToken(token: String) {
        runCatching {
            val conn = (java.net.URL("https://discord.com/api/oauth2/token/revoke")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "client_id=$APPLICATION_ID&token=" + java.net.URLEncoder.encode(token, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            Timber.tag(TAG).i("Discord token revoke -> HTTP %d", code)
        }.onFailure { Timber.tag(TAG).w(it, "Discord token revoke failed (offline?)") }
    }

    /** Attach to the session player from MusicService. Returns the listener to remove later. */
    fun attach(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                    )
                ) {
                    publishFrom(player)
                }
            }
        }
        player.addListener(listener)
        publishFrom(player)
        return listener
    }

    // ------------------------------------------------------------------ internals

    private val YT_THUMB = Regex("^https://(i\\d?\\.ytimg\\.com|img\\.youtube\\.com)/vi(?:_webp)?/([^/]+)/")

    /**
     * An image URL Discord's proxy can always fetch. The player's artwork is often a
     * `maxresdefault.jpg` video thumbnail (the app's "original quality" upgrade), which many
     * videos — typical for playlist entries — don't have: in-app image loading falls back, but
     * Discord just showed "?". Also unwraps the app's internal content:// artwork wrapper.
     */
    private fun discordArtworkUrl(raw: String?, videoId: String?): String? {
        val unwrapped = com.unshoo.pixelmusic.utils.MediaItemBuilder.unwrapArtworkUri(raw) ?: raw
        val url = unwrapped?.takeIf { it.startsWith("https://") }
        val fixed = when {
            url == null -> null
            YT_THUMB.containsMatchIn(url) ->
                YT_THUMB.find(url)?.groupValues?.get(2)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
                // Same image, a size YouTube Music always serves (and small enough for Discord).
                url.replace(Regex("=(w\\d+-h\\d+|s\\d+).*$"), "=w544-h544-l90-rj")
            else -> url
        }?.takeIf { it.length <= 256 }
        val chosen = fixed ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        android.util.Log.i(TAG, "presence artwork: ${chosen ?: "(app logo)"}")
        return chosen
    }

    /** Reads player state on the main thread, then hands a snapshot to the Discord thread. */
    private fun publishFrom(player: Player) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { publishFrom(player) }
            return
        }
        val md = player.mediaMetadata
        val now = System.currentTimeMillis()
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val videoId = youtubeIdOf(player.currentMediaItem)
        val art = discordArtworkUrl(md.artworkUri?.toString(), videoId)
        val snapshot = Snapshot(
            playing = player.isPlaying,
            videoId = videoId,
            title = md.title?.toString().orEmpty(),
            artist = (md.artist ?: md.albumArtist)?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artUrl = art,
            startMs = now - position,
            endMs = if (duration > 0) now - position + duration else 0L,
        )
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(300) // coalesce bursts (transition + metadata + play fire together)
            latest = snapshot
            applyPresence()
        }
    }

    /** True while a status is currently shown on Discord (so we only clear when needed). */
    private var presenceVisible = false

    private fun applyPresence() {
        val snap = latest ?: return
        if (!started) return
        if (snap.sameAs(applied)) return
        val opts = _state.value.options
        val hide = !opts.showActivity ||
            snap.title.isBlank() ||
            (opts.hideLocalSongs && snap.videoId == null) ||
            (!snap.playing && !opts.showWhilePaused)
        if (hide) {
            if (presenceVisible) DiscordNative.nativeClearPresence()
            presenceVisible = false
            if (_state.value.linked || _state.value.viaApp) {
                val why = when {
                    !opts.showActivity -> "activity is turned off"
                    opts.hideLocalSongs && snap.videoId == null -> "local songs are hidden"
                    !snap.playing -> "hidden while paused"
                    else -> "your status updates when music plays"
                }
                _state.update { it.copy(status = "Connected — $why", error = null) }
            }
        } else {
            val paused = !snap.playing
            val artistLine = snap.artist.takeIf { it.isNotBlank() }
            DiscordNative.nativeSetPresence(
                title = snap.title,
                // Paused used to clear the status entirely; now it stays as "Paused".
                stateText = when {
                    paused && artistLine != null -> "Paused · $artistLine"
                    paused -> "Paused"
                    artistLine != null -> "by $artistLine"
                    else -> ""
                },
                album = snap.album,
                largeImage = snap.artUrl ?: LOGO_ASSET,
                smallImage = if (snap.artUrl != null) LOGO_ASSET else "",
                // No running timer while paused (it would keep counting up).
                startMs = if (paused) 0L else snap.startMs,
                endMs = if (paused) 0L else snap.endMs,
                // Join only makes sense while playing (it starts at the host's position).
                partyId = if (snap.videoId != null && !paused) sessionPartyId else "",
                joinSecret = snap.videoId?.takeIf { !paused }?.let { joinSecretFor(it, snap.startMs) }.orEmpty(),
                buttonUrl = snap.videoId?.let { "$SITE/listen?v=$it" }.orEmpty(),
                statusDisplayType = if (opts.songNameInList) 2 else 0,
            )
            presenceVisible = true
        }
        applied = snap
    }

    /** Changes one dropdown option, saves it, and re-sends (or clears) the status right away. */
    fun updateOptions(change: (Options) -> Options) {
        scope.launch {
            val ctx = appContext ?: return@launch
            val updated = change(_state.value.options)
            saveOptions(ctx, updated)
            _state.update { it.copy(options = updated) }
            applied = null
            applyPresence()
        }
    }

    private fun loadOptions(ctx: Context): Options {
        val p = ctx.getSharedPreferences(PREFS_NAME_FLAGS, Context.MODE_PRIVATE)
        val d = Options()
        return Options(
            showActivity = p.getBoolean(KEY_OPT_SHOW, d.showActivity),
            songNameInList = p.getBoolean(KEY_OPT_SONG_NAME, d.songNameInList),
            showWhilePaused = p.getBoolean(KEY_OPT_PAUSED, d.showWhilePaused),
            hideLocalSongs = p.getBoolean(KEY_OPT_HIDE_LOCAL, d.hideLocalSongs),
        )
    }

    private fun saveOptions(ctx: Context, o: Options) {
        ctx.getSharedPreferences(PREFS_NAME_FLAGS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OPT_SHOW, o.showActivity)
            .putBoolean(KEY_OPT_SONG_NAME, o.songNameInList)
            .putBoolean(KEY_OPT_PAUSED, o.showWhilePaused)
            .putBoolean(KEY_OPT_HIDE_LOCAL, o.hideLocalSongs)
            .apply()
    }

    private fun start(activity: Activity): Boolean {
        if (started) return true
        if (!DiscordNative.load()) {
            setStatus("Discord isn't supported on this device", error = "Native library failed to load")
            return false
        }
        return runCatching {
            // Gives the SDK its Android context and the Activity it opens the auth screen from.
            setSdkEngineActivity(activity)
            DiscordNative.nativeInit(sink, APPLICATION_ID)
            started = true
            startPump()
            prefs()?.let { p -> if (p.hasTokens()) connectWithSavedToken(p) }
            // Without a linked account the SDK can still publish through the installed,
            // signed-in Discord app.
            applied = null
            applyPresence()
            true
        }.getOrElse {
            Timber.tag(TAG).e(it, "Discord SDK start failed")
            setStatus("Couldn't start Discord", error = it.message)
            false
        }
    }

    /** One party id per app session so friends who join see the same "listen along" party. */
    private val sessionPartyId = "pm-" + java.util.UUID.randomUUID().toString().take(12)

    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private fun youtubeIdOf(item: androidx.media3.common.MediaItem?): String? {
        item ?: return null
        val id = item.mediaId
        if (id.startsWith("youtube_")) return id.removePrefix("youtube_").takeIf { YT_ID.matches(it) }
        val uri = item.localConfiguration?.uri
        if (uri?.scheme == "youtube") {
            return uri.schemeSpecificPart.trimStart('/').takeIf { YT_ID.matches(it) }
        }
        return null
    }

    /** "pm1.<videoId>.<songStartEpochMs>" — the joiner starts the song where the host is. */
    private fun joinSecretFor(videoId: String, songStartEpochMs: Long) = "pm1.$videoId.$songStartEpochMs"

    data class JoinTarget(val videoId: String, val positionMs: Long)

    /** Parses a join secret (from pixelmusic.me/_discord/join?secret=...) into what to play. */
    fun parseJoinSecret(secret: String?): JoinTarget? {
        val parts = secret?.split('.') ?: return null
        if (parts.size != 3 || parts[0] != "pm1" || !YT_ID.matches(parts[1])) return null
        val start = parts[2].toLongOrNull() ?: return null
        val position = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        return JoinTarget(parts[1], position)
    }

    /**
     * `DiscordSocialSdkInit.setEngineActivity` via reflection: the SDK AAR is only on the
     * classpath in builds that include Discord (BuildConfig.DISCORD_ENABLED), so the app must
     * still compile without it.
     */
    private fun setSdkEngineActivity(activity: Activity) {
        Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
            .getMethod("setEngineActivity", Activity::class.java)
            .invoke(null, activity)
    }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (isActive) {
                DiscordNative.nativeRunCallbacks()
                // Fast while a login/connection is in flight, slow when idle.
                delay(if (awaitingAuth || _state.value.busy) 100L else 1_000L)
            }
        }
    }

    private fun connectWithSavedToken(prefs: SharedPreferences) {
        val access = prefs.getString(KEY_ACCESS, null) ?: return
        val refresh = prefs.getString(KEY_REFRESH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        setStatus("Connecting…", busy = true)
        if (refresh != null && expiresAt - System.currentTimeMillis() < ONE_DAY_MS) {
            DiscordNative.nativeRefreshToken(refresh)
        } else {
            DiscordNative.nativeConnect(access)
        }
    }

    private val sink = object : DiscordNative.Sink {
        override fun onStatus(status: Int, error: String, errorDetail: Int) {
            when (status) {
                DiscordNative.STATUS_READY -> {
                    _state.update { it.copy(linked = true, busy = false, status = "Connected", error = null) }
                    applied = null
                    applyPresence()
                }
                DiscordNative.STATUS_CONNECTING,
                DiscordNative.STATUS_CONNECTED,
                DiscordNative.STATUS_RECONNECTING -> setStatus("Connecting…", busy = true)
                DiscordNative.STATUS_DISCONNECTED -> {
                    val err = error.takeUnless { it.isBlank() || it.equals("None", true) }
                    // In app-relay mode there is no gateway connection; ignore its idle status.
                    if (!_state.value.viaApp) {
                        setStatus(if (err != null) "Disconnected" else "Not connected", error = err)
                    }
                }
            }
        }

        override fun onTokens(accessToken: String, refreshToken: String, expiresInSeconds: Int) {
            awaitingAuth = false
            prefs()?.edit()
                ?.putString(KEY_ACCESS, accessToken)
                ?.putString(KEY_REFRESH, refreshToken)
                ?.putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1_000L)
                ?.apply()
            _state.update { it.copy(linked = true) }
            setStatus("Connecting…", busy = true)
            DiscordNative.nativeConnect(accessToken)
        }

        override fun onPresence(success: Boolean, message: String) {
            if (success) {
                val shown = applied?.takeIf { presenceVisible }
                _state.update {
                    it.copy(
                        error = null,
                        busy = false,
                        status = when {
                            shown != null && shown.playing -> "Showing on Discord: ${shown.title}"
                            shown != null -> "Showing on Discord: Paused · ${shown.title}"
                            it.viaApp || it.linked -> "Connected — your status updates when music plays"
                            else -> it.status
                        },
                    )
                }
            } else {
                _state.update { it.copy(error = "Discord rejected the status: $message") }
            }
        }

        override fun onError(where: String, message: String) {
            Timber.tag(TAG).w("Discord %s error: %s", where, message)
            awaitingAuth = false
            if (where == "token" || where == "updateToken") {
                // Expired / revoked login: forget it so the next Connect starts a fresh login.
                prefs()?.edit()?.remove(KEY_ACCESS)?.remove(KEY_REFRESH)?.remove(KEY_EXPIRES_AT)?.apply()
                _state.update { it.copy(linked = false) }
            }
            if (where == "presence") {
                _state.update { it.copy(error = message) }
            } else {
                setStatus("Not connected", error = message)
            }
        }
    }

    private fun setStatus(status: String, busy: Boolean = false, error: String? = null) {
        _state.update { it.copy(status = status, busy = busy, error = error) }
    }

    private fun prefs(): SharedPreferences? {
        prefsInstance?.let { return it }
        val ctx = appContext ?: return null
        val created = try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Encrypted prefs unavailable, using private prefs")
            ctx.getSharedPreferences(PREFS_NAME_PLAIN, Context.MODE_PRIVATE)
        }
        prefsInstance = created
        return created
    }

    private fun isEnabledFlag(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME_FLAGS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun setEnabledFlag(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME_FLAGS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).commit()
    }

    private fun SharedPreferences.hasTokens() = getString(KEY_ACCESS, null) != null

    private const val TAG = "DiscordPresence"
    const val SITE = "https://www.pixelmusic.me"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OPT_SHOW = "opt_show_activity"
    private const val KEY_OPT_SONG_NAME = "opt_song_name_in_list"
    private const val KEY_OPT_PAUSED = "opt_show_while_paused"
    private const val KEY_OPT_HIDE_LOCAL = "opt_hide_local_songs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val PREFS_NAME = "discord_presence"
    private const val PREFS_NAME_PLAIN = "discord_presence_plain"
    /** Non-secret on/off flag only (no tokens), readable without the Keystore. */
    private const val PREFS_NAME_FLAGS = "discord_presence_flags"
}
