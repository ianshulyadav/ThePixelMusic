package com.unshoo.pixelmusic.data.network

import com.unshoo.pixelmusic.data.preferences.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AppProxyManagerTest {

    @BeforeEach
    fun setUp() {
        AppProxyManager.clearProxy()
    }

    @AfterEach
    fun tearDown() {
        AppProxyManager.clearProxy()
    }

    @Test
    fun `applyProxy with disabled config clears all JVM system properties`() {
        // First set some proxy properties
        System.setProperty("http.proxyHost", "1.2.3.4")
        System.setProperty("socksProxyHost", "1.2.3.4")

        val config = ProxyConfig(
            enabled = false,
            type = ProxyType.HTTP,
            host = "1.2.3.4",
            port = 8080
        )
        AppProxyManager.applyProxy(config)

        assertNull(System.getProperty("http.proxyHost"))
        assertNull(System.getProperty("http.proxyPort"))
        assertNull(System.getProperty("https.proxyHost"))
        assertNull(System.getProperty("https.proxyPort"))
        assertNull(System.getProperty("socksProxyHost"))
        assertNull(System.getProperty("socksProxyPort"))
        assertFalse(AppProxyManager.currentConfig.enabled)
    }

    @Test
    fun `applyProxy with HTTP sets http and https JVM system properties and unsets socks`() {
        val config = ProxyConfig(
            enabled = true,
            type = ProxyType.HTTP,
            host = "10.0.0.1",
            port = 3128,
            username = "user",
            password = "pass"
        )
        AppProxyManager.applyProxy(config)

        assertEquals("10.0.0.1", System.getProperty("http.proxyHost"))
        assertEquals("3128", System.getProperty("http.proxyPort"))
        assertEquals("10.0.0.1", System.getProperty("https.proxyHost"))
        assertEquals("3128", System.getProperty("https.proxyPort"))
        assertNull(System.getProperty("socksProxyHost"))
        assertNull(System.getProperty("socksProxyPort"))
        assertTrue(AppProxyManager.currentConfig.enabled)
        assertEquals(ProxyType.HTTP, AppProxyManager.currentConfig.type)
    }

    @Test
    fun `applyProxy with SOCKS5 sets socks JVM system properties and unsets http`() {
        val config = ProxyConfig(
            enabled = true,
            type = ProxyType.SOCKS,
            host = "192.168.1.50",
            port = 1080,
            username = "socksUser",
            password = "socksPassword"
        )
        AppProxyManager.applyProxy(config)

        assertEquals("192.168.1.50", System.getProperty("socksProxyHost"))
        assertEquals("1080", System.getProperty("socksProxyPort"))
        assertEquals("socksUser", System.getProperty("java.net.socks.username"))
        assertEquals("socksPassword", System.getProperty("java.net.socks.password"))
        assertNull(System.getProperty("http.proxyHost"))
        assertNull(System.getProperty("https.proxyHost"))
        assertTrue(AppProxyManager.currentConfig.enabled)
        assertEquals(ProxyType.SOCKS, AppProxyManager.currentConfig.type)
    }

    @Test
    fun `testProxyConnection to an unreachable port returns Failure with meaningful error`() = runTest {
        // Port 59871 on localhost where nothing is listening
        val result = AppProxyManager.testProxyConnection(
            type = ProxyType.HTTP,
            host = "127.0.0.1",
            port = 59871
        )

        assertTrue(result is ProxyTestResult.Failure)
        val failure = result as ProxyTestResult.Failure
        assertTrue(failure.errorMessage.isNotBlank())
    }

    @Test
    fun `testProxyConnection succeeds when mock proxy returns HTTP 204`() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        val isRunning = AtomicBoolean(true)

        val serverThread = thread {
            try {
                while (isRunning.get()) {
                    val client: Socket = server.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    // Read headers until empty line
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = reader.readLine()
                    }
                    val response = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    client.close()
                }
            } catch (_: Exception) {
            }
        }

        try {
            val result = AppProxyManager.testProxyConnection(
                type = ProxyType.HTTP,
                host = "127.0.0.1",
                port = port,
                testUrl = "http://127.0.0.1:$port/generate_204"
            )
            assertTrue(result is ProxyTestResult.Success, "Expected success but got: $result")
            val success = result as ProxyTestResult.Success
            assertTrue(success.latencyMs >= 0)
        } finally {
            isRunning.set(false)
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun `testProxyConnection reports Failure when mock proxy returns 502 Bad Gateway`() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        val isRunning = AtomicBoolean(true)

        val serverThread = thread {
            try {
                while (isRunning.get()) {
                    val client: Socket = server.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = reader.readLine()
                    }
                    val response = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    client.close()
                }
            } catch (_: Exception) {
            }
        }

        try {
            val result = AppProxyManager.testProxyConnection(
                type = ProxyType.HTTP,
                host = "127.0.0.1",
                port = port,
                testUrl = "http://127.0.0.1:$port/generate_204"
            )
            assertTrue(result is ProxyTestResult.Failure, "Expected failure for 502 Bad Gateway")
            val failure = result as ProxyTestResult.Failure
            assertTrue(failure.errorMessage.contains("502"), "Message should mention HTTP 502, got: ${failure.errorMessage}")
        } finally {
            isRunning.set(false)
            server.close()
            serverThread.join(1000)
        }
    }
}

