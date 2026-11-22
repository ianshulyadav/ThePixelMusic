package com.unshoo.pixelmusic.data.github

import com.unshoo.pixelmusic.BuildConfig
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Remote knobs for the December 14th birthday surprise, read from the same
 * `remote-config/app-announcements.properties` file as the Play Store announcement.
 *
 * Keys (all optional):
 * - birthday_surprise_enabled   — kill switch; the popup shows unless this is explicitly false
 * - birthday_wish_endpoint      — HTTPS URL that receives wishes as JSON (POST) and feeds the website
 * - birthday_wishes_page_url    — public page on the PixelMusic website where wishes are displayed
 */
data class BirthdaySurpriseRemoteConfig(
    val enabled: Boolean = true,
    val wishEndpoint: String? = DEFAULT_WISH_ENDPOINT,
    val wishesPageUrl: String = DEFAULT_WISHES_PAGE_URL,
) {
    companion object {
        const val DEFAULT_WISH_ENDPOINT = "https://www.pixelmusic.me/api/wishes"
        const val DEFAULT_WISHES_PAGE_URL = "https://www.pixelmusic.me/#wishes"

        /**
         * Remote overrides may only point at PixelMusic's own website. Only the `www`
         * host: the apex domain 308-redirects, which would drop a POST.
         */
        val TRUSTED_HOSTS = setOf("www.pixelmusic.me")

        fun isTrustedUrl(url: String?): Boolean {
            val uri = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in TRUSTED_HOSTS
        }
    }
}

/**
 * Birthday mode shared with the website, set from www.pixelmusic.me/wishes/admin.html.
 * [mode] is "auto" (show on [date] every year), "on" (show now) or "off" (never).
 */
data class BirthdaySiteConfig(
    val mode: String = MODE_AUTO,
    val date: java.time.MonthDay = DEFAULT_DATE,
    /** Announcements published from the website admin page, newest first. */
    val announcements: List<AppAnnouncement> = emptyList(),
) {
    /** The newest announcement (shown once as a popup), if any. */
    val announcement: AppAnnouncement? get() = announcements.firstOrNull()

    fun isActiveOn(day: java.time.LocalDate): Boolean = when (mode) {
        MODE_ON -> true
        MODE_OFF -> false
        else -> java.time.MonthDay.from(day) == date
    }

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_ON = "on"
        const val MODE_OFF = "off"
        val DEFAULT_DATE: java.time.MonthDay = java.time.MonthDay.of(12, 14)

        fun parse(mode: String?, date: String?): BirthdaySiteConfig {
            val parsedMode = mode?.takeIf { it == MODE_AUTO || it == MODE_ON || it == MODE_OFF } ?: MODE_AUTO
            val parsedDate = date
                ?.let { runCatching { java.time.MonthDay.parse("--$it") }.getOrNull() }
                ?: DEFAULT_DATE
            return BirthdaySiteConfig(parsedMode, parsedDate)
        }

        /** Parses the full `GET ?config=1` response, including the announcement. */
        fun fromJson(raw: String?): BirthdaySiteConfig? {
            val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val list = json.optJSONArray("announcements")
            val announcements = if (list != null) {
                (0 until list.length()).mapNotNull { AppAnnouncement.fromJson(list.optJSONObject(it)) }
            } else {
                // Older server: only the newest announcement.
                listOfNotNull(AppAnnouncement.fromJson(json.optJSONObject("announcement")))
            }
            return parse(json.optString("mode"), json.optString("date")).copy(announcements = announcements)
        }
    }
}

/** An announcement the team publishes from www.pixelmusic.me/wishes/admin.html. */
data class AppAnnouncement(
    val id: String,
    val title: String,
    val message: String,
    val buttonLabel: String?,
    val buttonUrl: String?,
    /** Last day (inclusive) to show it as a popup, or null for no end date. */
    val until: java.time.LocalDate?,
    /** Publish time in epoch millis, or 0 if unknown. */
    val publishedAt: Long = 0L,
) {
    fun isVisibleOn(day: java.time.LocalDate): Boolean = until == null || !day.isAfter(until)

    companion object {
        fun fromJson(json: JSONObject?): AppAnnouncement? {
            if (json == null) return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
            val url = json.optString("buttonUrl").trim().takeIf { it.startsWith("https://") }
            return AppAnnouncement(
                id = id,
                title = title.take(80),
                message = json.optString("message").take(600),
                buttonLabel = json.optString("buttonLabel").trim().takeIf { url != null && it.isNotBlank() }?.take(30),
                buttonUrl = url,
                until = json.optString("until").takeIf { it.isNotBlank() }
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                publishedAt = json.optLong("publishedAt", 0L),
            )
        }
    }
}

sealed interface BirthdayWishResult {
    data object Sent : BirthdayWishResult
    data object NotConfigured : BirthdayWishResult
    /** The server refused the wish: `not_kind`, `no_links`, `slow_down`, `message_required`. */
    data class Rejected(val reason: String) : BirthdayWishResult
    data class Failed(val error: Throwable) : BirthdayWishResult
}

@Singleton
class BirthdaySurpriseService @Inject constructor() {

    suspend fun fetchConfig(
        owner: String = "ianshulyadav",
        repo: String = "PixelMusic",
        branch: String = "master",
        configPath: String = "remote-config/app-announcements.properties",
    ): BirthdaySurpriseRemoteConfig = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$configPath"
            connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                addRequestProperty("Accept", "text/plain")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext BirthdaySurpriseRemoteConfig()
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val props = Properties().apply { load(StringReader(response)) }
            BirthdaySurpriseRemoteConfig(
                enabled = when (props.getProperty("birthday_surprise_enabled")?.trim()?.lowercase()) {
                    "false", "0", "no", "off" -> false
                    else -> true
                },
                wishEndpoint = props.getProperty("birthday_wish_endpoint")?.trim()
                    ?.takeIf { BirthdaySurpriseRemoteConfig.isTrustedUrl(it) }
                    ?: BirthdaySurpriseRemoteConfig.DEFAULT_WISH_ENDPOINT,
                wishesPageUrl = props.getProperty("birthday_wishes_page_url")?.trim()
                    ?.takeIf { BirthdaySurpriseRemoteConfig.isTrustedUrl(it) }
                    ?: BirthdaySurpriseRemoteConfig.DEFAULT_WISHES_PAGE_URL,
            )
        } catch (e: Exception) {
            Timber.w(e, "Birthday surprise config unavailable; using defaults")
            BirthdaySurpriseRemoteConfig()
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads the birthday mode and date the team sets on the website's admin page
     * (`GET <wishes endpoint>?config=1` -> `{ "mode": "auto|on|off", "date": "MM-DD" }`).
     * Returns the raw JSON (also carries the app announcement), or null when the website
     * can't be reached.
     */
    suspend fun fetchSiteConfigJson(endpoint: String?): String? = withContext(Dispatchers.IO) {
        if (!BirthdaySurpriseRemoteConfig.isTrustedUrl(endpoint)) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$endpoint?config=1").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            raw.takeIf { BirthdaySiteConfig.fromJson(it) != null }
        } catch (e: Exception) {
            Timber.w(e, "Birthday site config unavailable")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Posts `{name, message, platform, appVersion, sentAt}` as JSON to [endpoint]. */
    suspend fun sendWish(endpoint: String?, name: String, message: String): BirthdayWishResult =
        withContext(Dispatchers.IO) {
            if (endpoint.isNullOrBlank()) return@withContext BirthdayWishResult.NotConfigured
            var connection: HttpURLConnection? = null
            try {
                val payload = JSONObject()
                    .put("name", name.trim().ifBlank { "A PixelMusic listener" }.take(MAX_NAME_LENGTH))
                    .put("message", message.trim().take(MAX_MESSAGE_LENGTH))
                    .put("platform", "android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("sentAt", System.currentTimeMillis())
                    .toString()
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                when {
                    code in 200..299 -> BirthdayWishResult.Sent
                    code == 503 -> BirthdayWishResult.NotConfigured
                    code == 422 || code == 429 -> {
                        val reason = connection.errorStream?.bufferedReader()?.use { it.readText() }
                            ?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                            .orEmpty()
                            .ifBlank { if (code == 429) "slow_down" else "not_kind" }
                        BirthdayWishResult.Rejected(reason)
                    }
                    else -> BirthdayWishResult.Failed(IllegalStateException("Wish endpoint returned HTTP $code"))
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to send birthday wish")
                BirthdayWishResult.Failed(e)
            } finally {
                connection?.disconnect()
            }
        }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_MESSAGE_LENGTH = 280
    }
}
