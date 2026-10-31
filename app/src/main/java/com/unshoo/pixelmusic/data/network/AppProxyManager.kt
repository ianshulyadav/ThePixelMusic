package com.unshoo.pixelmusic.data.network

import com.unshoo.pixelmusic.data.preferences.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Data representation of the user-configured proxy.
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = ""
) {
    val isValid: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535
}

sealed class ProxyTestResult {
    data class Success(val latencyMs: Long) : ProxyTestResult()
    data class Failure(val errorMessage: String) : ProxyTestResult()
}

/**
 * Centralized manager for application-wide network proxy routing.
 * Ensures consistent routing, authentication, and DNS resolution across:
 * - InnerTube (YouTube Music API via Ktor/OkHttp)
 * - YoutubeHelper (stream metadata & format extraction)
 * - NewPipe (fallback signature / stream resolution)
 * - ExoPlayer (DefaultHttpDataSource media streaming)
 */
object AppProxyManager {

    private val currentConfigRef = AtomicReference<ProxyConfig>(ProxyConfig())

    val currentConfig: ProxyConfig
        get() = currentConfigRef.get()

    fun clearProxy() {
        applyProxy(ProxyConfig(enabled = false))
    }

    @Volatile
    private var isJvmAuthenticatorInstalled = false

    /**
     * Installs a JVM-wide Authenticator to support SOCKS5 and HTTP proxy authentication
     * across standard Java networking (e.g. HttpURLConnection used by ExoPlayer's DefaultHttpDataSource).
     */
    @Synchronized
    private fun ensureJvmAuthenticatorInstalled() {
        if (!isJvmAuthenticatorInstalled) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val config = currentConfigRef.get()
                    if (config.isValid && config.username.isNotBlank() && config.password.isNotBlank()) {
                        return PasswordAuthentication(config.username, config.password.toCharArray())
                    }
                    return null
                }
            })
            isJvmAuthenticatorInstalled = true
        }
    }

    /**
     * Applies the current proxy configuration to system properties, InnerTube, and YouTube clients.
     */
    @Synchronized
    fun applyProxy(config: ProxyConfig) {
        currentConfigRef.set(config)
        ensureJvmAuthenticatorInstalled()

        if (config.isValid) {
            val javaType = when (config.type) {
                ProxyType.SOCKS -> Proxy.Type.SOCKS
                ProxyType.HTTP -> Proxy.Type.HTTP
            }
            // Use createUnresolved so that DNS is performed by the proxy server, avoiding local DNS leaks and failures
            val socketAddress = InetSocketAddress.createUnresolved(config.host, config.port)
            val javaProxy = Proxy(javaType, socketAddress)

            // 1. Update InnerTube / YouTube singleton
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxy = javaProxy
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxyUsername = config.username.ifBlank { null }
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxyPassword = config.password.ifBlank { null }

            // 2. Set JVM-wide system properties so HttpURLConnection (ExoPlayer DefaultHttpDataSource) routes correctly
            try {
                if (config.type == ProxyType.HTTP) {
                    System.setProperty("http.proxyHost", config.host)
                    System.setProperty("http.proxyPort", config.port.toString())
                    System.setProperty("https.proxyHost", config.host)
                    System.setProperty("https.proxyPort", config.port.toString())
                    clearSocksSystemProperties()
                } else {
                    System.setProperty("socksProxyHost", config.host)
                    System.setProperty("socksProxyPort", config.port.toString())
                    if (config.username.isNotBlank()) {
                        System.setProperty("java.net.socks.username", config.username)
                        System.setProperty("java.net.socks.password", config.password)
                    } else {
                        System.clearProperty("java.net.socks.username")
                        System.clearProperty("java.net.socks.password")
                    }
                    clearHttpSystemProperties()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed setting proxy system properties")
            }

            // 3. Update YoutubeHelper's client
            com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.updateProxy(
                proxy = javaProxy,
                username = config.username.ifBlank { null },
                password = config.password.ifBlank { null }
            )

            Timber.i("AppProxyManager: Proxy applied successfully (${config.type} -> ${config.host}:${config.port})")
        } else {
            // Disabled or invalid: restore direct connections
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxy = null
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxyUsername = null
            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.proxyPassword = null

            clearHttpSystemProperties()
            clearSocksSystemProperties()

            com.unshoo.pixelmusic.data.remote.youtube.YoutubeHelper.updateProxy(null, null, null)
            Timber.i("AppProxyManager: Proxy disabled (direct connection)")
        }
    }

    private fun clearHttpSystemProperties() {
        try {
            System.clearProperty("http.proxyHost")
            System.clearProperty("http.proxyPort")
            System.clearProperty("https.proxyHost")
            System.clearProperty("https.proxyPort")
        } catch (_: Exception) {}
    }

    private fun clearSocksSystemProperties() {
        try {
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
            System.clearProperty("java.net.socks.username")
            System.clearProperty("java.net.socks.password")
        } catch (_: Exception) {}
    }

    /**
     * Builds an isolated OkHttpClient for testing a proxy configuration without mutating global state.
     */
    suspend fun testProxyConnection(
        type: ProxyType,
        host: String,
        port: Int,
        username: String = "",
        password: String = "",
        testUrl: String = "https://music.youtube.com/generate_204"
    ): ProxyTestResult = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext ProxyTestResult.Failure("Invalid host or port")
        }

        val javaType = when (type) {
            ProxyType.SOCKS -> Proxy.Type.SOCKS
            ProxyType.HTTP -> Proxy.Type.HTTP
        }
        val proxy = Proxy(javaType, InetSocketAddress.createUnresolved(host.trim(), port))

        val clientBuilder = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        if (type == ProxyType.HTTP && username.isNotBlank() && password.isNotBlank()) {
            clientBuilder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.responseCount() >= 3) return null // Prevent auth loops
                    val credential = Credentials.basic(username.trim(), password)
                    return response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            })
        }

        // For SOCKS5, temporary Authenticator is checked if challenged
        val testClient = clientBuilder.build()
        val startTime = System.currentTimeMillis()

        // Test with YouTube Music 204 endpoint or provided testUrl
        val request = Request.Builder()
            .url(testUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        try {
            testClient.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful || response.code == 204) {
                    ProxyTestResult.Success(duration)
                } else if (response.code == 407) {
                    ProxyTestResult.Failure("Proxy Authentication Required (HTTP 407)")
                } else {
                    ProxyTestResult.Failure("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            ProxyTestResult.Failure("Connection timed out (10s)")
        } catch (e: java.net.ConnectException) {
            ProxyTestResult.Failure("Connection refused: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            ProxyTestResult.Failure("Unknown host: ${e.message}")
        } catch (e: Exception) {
            ProxyTestResult.Failure(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun Response.responseCount(): Int {
        var result = 1
        var prior = priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}

