/*
 * Ported from ArchiveTune (2026) — © Rukamori, GPL-3.0.
 * Origin: canvas/src/main/kotlin/moe/rukamori/archivetune/canvas/AppleMusicProvider.kt
 *
 * PixelMusic keeps only the parts the audio path needs: the developer-token providers, the
 * web-player JWT scraper (with the hardcoded fallback token) and the token-freshness logic.
 * The canvas/artwork search that lived in the same object upstream is not used here.
 * The Ktor client was replaced with OkHttp (PixelMusic already ships OkHttp 5).
 */

package com.unshoo.pixelmusic.data.lossless.applemusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object AppleMusicProvider {

    private const val LOG_TAG = "AppleMusicToken"

    /** Returns the user-pasted developer token (Settings → Lossless Sources → Apple Music), if any. */
    @Volatile
    var devTokenProvider: (() -> String?)? = null

    /** Returns the Media-User-Token to use: the user's own login first, then the first pool account. */
    @Volatile
    var mediaUserTokenProvider: (() -> String?)? = null

    private val fallbackAppleMusicToken: String =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkyNzgwNTM0LCJleHAiOjE3OTg4" +
            "Mjg1MzQsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".wcf_7U-zcgUjY2L8d4HWFkdxP1YSJCkkqEpEtVPKpRARrlaag0DHyIpRcoUEWZU8kJlvx5Q7ZNp04OabfuiuAw"

    @Volatile
    private var appleMusicToken: String = fallbackAppleMusicToken

    @Volatile
    private var appleMusicTokenExpAtSec: Long = decodeJwtExpSec(fallbackAppleMusicToken)

    @Volatile
    private var appleMusicTokenLastRefreshAtMs: Long = 0L

    private val tokenRefreshMutex = Mutex()

    private const val APPLE_MUSIC_WEB_HOME = "https://music.apple.com/"
    private const val APPLE_MUSIC_WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * The best available developer JWT: the user-pasted token when present,
     * otherwise the auto-scraped web-player token (refreshed when stale).
     * Lets the audio-provider search path work without a manually pasted
     * developer token.
     */
    suspend fun currentDevToken(): String? = ensureTokenFresh().trim().takeIf { it.isNotBlank() }

    suspend fun refreshToken(): String? =
        tokenRefreshMutex.withLock {
            appleMusicTokenLastRefreshAtMs = System.currentTimeMillis()
            val fresh = scrapeTokenFromWeb()
            if (fresh != null) {
                appleMusicToken = fresh
                appleMusicTokenExpAtSec = decodeJwtExpSec(fresh)
                Timber.tag(LOG_TAG).d("Apple Music token refreshed from web player (exp=%ds)", appleMusicTokenExpAtSec)
            } else {
                Timber.tag(LOG_TAG).w("Apple Music token refresh failed — falling back to hardcoded token")
            }
            fresh
        }

    fun isJwtExpired(jwt: String, marginSec: Long = 60L): Boolean {
        val exp = decodeJwtExpSec(jwt)
        val nowSec = System.currentTimeMillis() / 1000L
        return exp != 0L && exp <= (nowSec + marginSec)
    }

    fun getFreshDevToken(): String {
        devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() && !isJwtExpired(it) }?.let { return it }
        val current = appleMusicToken
        if (!isJwtExpired(current)) return current
        return fallbackAppleMusicToken
    }

    private suspend fun ensureTokenFresh(): String {
        devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.let { userDevToken ->
            return userDevToken
        }
        val nowSec = System.currentTimeMillis() / 1000L
        val isExpired = appleMusicTokenExpAtSec == 0L || appleMusicTokenExpAtSec <= nowSec
        val needsRefresh =
            appleMusicTokenExpAtSec == 0L || appleMusicTokenExpAtSec - nowSec < 60L * 60L * 24L
        if (!needsRefresh) return appleMusicToken

        val sinceLast = System.currentTimeMillis() - appleMusicTokenLastRefreshAtMs
        if (!isExpired && sinceLast in 1..60_000L) return appleMusicToken

        if (!isExpired) return appleMusicToken

        return refreshToken() ?: appleMusicToken
    }

    private fun get(url: String): Pair<Int, String>? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", APPLE_MUSIC_WEB_UA)
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .header("Accept", "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()
            tokenClient.newCall(request).execute().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        }.getOrNull()

    private suspend fun scrapeTokenFromWeb(): String? =
        withContext(Dispatchers.IO) {
            try {
                val targets = listOf("https://music.apple.com/us/browse", APPLE_MUSIC_WEB_HOME)
                for (target in targets) {
                    val (code, html) = get(target) ?: continue
                    if (code !in 200..299) continue
                    pickAmpToken(html)?.let { return@withContext it }
                    val bundleUrls = jsBundleUrls(html)
                    for (jsBundleUrl in bundleUrls) {
                        val (jsCode, js) = get(jsBundleUrl) ?: continue
                        if (jsCode !in 200..299) continue
                        pickAmpToken(js)?.let { return@withContext it }
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(LOG_TAG).e(e, "Apple Music token scrape error")
                null
            }
        }

    private fun pickAmpToken(text: String): String? {
        val nowSec = System.currentTimeMillis() / 1000L
        val candidates = directJwtRegex.findAll(text).map { it.value }.distinct().toList()
        if (candidates.isEmpty()) return null

        fun unexpired(jwt: String): Boolean {
            val exp = decodeJwtExpSec(jwt)
            return exp == 0L || exp > nowSec
        }

        return candidates.firstOrNull { jwt ->
            unexpired(jwt) && decodeJwtIssuer(jwt) == AMP_WEB_PLAY_ISSUER
        } ?: candidates.firstOrNull { unexpired(it) }
    }

    private fun jsBundleUrls(html: String): List<String> {
        val indexBundles =
            jsBundleRegex
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
        val anyBundles =
            anyJsAssetRegex
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
        return (indexBundles + anyBundles)
            .distinct()
            .map(::absoluteAppleMusicUrl)
            .take(4)
    }

    private fun absoluteAppleMusicUrl(rawUrl: String): String =
        when {
            rawUrl.startsWith("http") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "https://music.apple.com$rawUrl"
            else -> "https://music.apple.com/$rawUrl"
        }

    private val directJwtRegex: Regex =
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    private val jsBundleRegex: Regex =
        Regex("""(?:src|href)=["']([^"']*index[~\-][A-Za-z0-9._~-]+\.js)["']""")

    private val anyJsAssetRegex: Regex =
        Regex("""(?:src|data-src)=["']([^"']*/assets/[^"']+\.js)["']""")

    private const val AMP_WEB_PLAY_ISSUER = "AMPWebPlay"

    private fun decodeJwtExpSec(jwt: String): Long {
        val payload = decodeJwtPayload(jwt) ?: return 0L
        val expMatch = """"exp"\s*:\s*(\d+)""".toRegex().find(payload) ?: return 0L
        return expMatch.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun decodeJwtIssuer(jwt: String): String? {
        val payload = decodeJwtPayload(jwt) ?: return null
        return """"iss"\s*:\s*"([^"]+)"""".toRegex().find(payload)?.groupValues?.get(1)
    }

    private fun decodeJwtPayload(jwt: String): String? {
        val parts = jwt.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(java.util.Base64.getDecoder().decode(padded))
        }.getOrNull()
    }
}
