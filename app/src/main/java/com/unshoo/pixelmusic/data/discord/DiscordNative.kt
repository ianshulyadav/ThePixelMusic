package com.unshoo.pixelmusic.data.discord

import androidx.annotation.Keep

/**
 * JNI surface of `libpixelmusic_discord.so` (see `src/main/cpp/discord_bridge.cpp`).
 *
 * Only [DiscordPresence] may call these, and only from its single Discord thread: the SDK is not
 * thread-safe and delivers every callback from [nativeRunCallbacks] on the calling thread.
 * The native libraries are loaded lazily by [load], so users who never enable Discord pay nothing.
 */
@Keep
internal object DiscordNative {

    /** Receives SDK callbacks. Method names/signatures are looked up from C++; keep them stable. */
    @Keep
    interface Sink {
        fun onStatus(status: Int, error: String, errorDetail: Int)
        fun onTokens(accessToken: String, refreshToken: String, expiresInSeconds: Int)
        fun onError(where: String, message: String)
        fun onPresence(success: Boolean, message: String)
    }

    @Volatile
    private var loaded = false

    /** Loads the Discord SDK + bridge. Returns false if the device can't load them. */
    fun load(): Boolean {
        if (loaded) return true
        if (!com.unshoo.pixelmusic.BuildConfig.DISCORD_ENABLED) return false
        return runCatching {
            System.loadLibrary("discord_partner_sdk")
            System.loadLibrary("pixelmusic_discord")
            loaded = true
            true
        }.getOrElse { false }
    }

    // Client::Status values from discordpp.h
    const val STATUS_DISCONNECTED = 0
    const val STATUS_CONNECTING = 1
    const val STATUS_CONNECTED = 2
    const val STATUS_READY = 3
    const val STATUS_RECONNECTING = 4

    external fun nativeInit(sink: Sink, applicationId: Long)
    external fun nativeRunCallbacks()
    external fun nativeAuthorize()
    external fun nativeRefreshToken(refreshToken: String)
    external fun nativeConnect(accessToken: String)
    external fun nativeDisconnect()
    external fun nativeSetPresence(
        title: String,
        artist: String,
        album: String,
        largeImage: String,
        smallImage: String,
        startMs: Long,
        endMs: Long,
        partyId: String,
        joinSecret: String,
        buttonUrl: String,
    )
    external fun nativeClearPresence()
    /** Clears presence, disconnects and destroys the native client (full detach). */
    external fun nativeShutdown()
}
