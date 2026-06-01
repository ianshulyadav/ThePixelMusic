package com.unshoo.pixelmusic.data.telegram

import android.content.Context
import com.unshoo.pixelmusic.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramClientManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        @Volatile
        private var isLibraryLoaded = false
        private val libraryLoadLock = Any()

        fun ensureLibraryLoaded() {
            if (isLibraryLoaded) return
            synchronized(libraryLoadLock) {
                if (isLibraryLoaded) return
                try {
                    System.loadLibrary("tdjni")
                    isLibraryLoaded = true
                    Timber.d("TDLib native library loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Timber.e(e, "Failed to load TDLib native library")
                }
            }
        }
    }

    private val _authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authorizationState = _authorizationState.asStateFlow()

    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()

    private val _errors = MutableSharedFlow<TdApi.Error>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()

    @Volatile
    private var client: Client? = null
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    @Volatile
    private var recreateClientAfterClose = false

    // Handler for incoming updates from TDLib
    private val updateHandler = Client.ResultHandler { update ->
        if (update is TdApi.Update) {
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    onAuthorizationStateUpdated(update.authorizationState)
                }
                is TdApi.UpdateUser -> {
                    // Handle user updates if needed
                }
                is TdApi.UpdateFile -> {
                    _updates.tryEmit(update)
                }
                // Add other update handlers here
                else -> {}
            }
        } else if (update is TdApi.Error) {
            reportTdError(update)
        }
    }

    init {
        initializeClient()
    }

    @Synchronized
    private fun initializeClient() {
        if (client != null) return
        clientScope.launch {
            ensureLibraryLoaded()
            // Set log verbosity to 1 (Errors only) to prevent heavy logging
            try {
                Client.execute(TdApi.SetLogVerbosityLevel(1))
            } catch (e: Exception) {
                Timber.e(e, "Failed to set TDLib log verbosity")
            }

            // Create a new instance of TDLib Client
            synchronized(this@TelegramClientManager) {
                if (client == null) {
                    client = Client.create(updateHandler, null, null)
                }
            }
        }
    }

    private fun onAuthorizationStateUpdated(authState: TdApi.AuthorizationState) {
        _authorizationState.value = authState
        when (authState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val databaseDirectory = File(context.filesDir, "tdlib").absolutePath
                val filesDirectory = File(context.filesDir, "tdlib_files").absolutePath
                
                // Based on error message and typical TDLib params structure for flat constructors:
                // useTestDc, databaseDir, filesDir, encryptionKey, useFileDatabase, useChatInfoDatabase, useMessageDatabase, useSecretChats, apiId, apiHash, systemLanguage, deviceModel, systemVersion, applicationVersion, enableStorageOptimizer, ignoreFileNames
                
                // Note: The order varies by version. I will try the most common flat signature.
                // If this fails, I might need to revert to using the object but finding why the object constructor failed.
                // Actually, often in Java bindings, you have to set fields on the object passed to SetTdlibParameters.
                // But if SetTdlibParameters ONLY has a multi-arg constructor, I must use it.
                
                // Let's assume the error message `constructor(p0: Boolean, p1: String!, ...)` matches the fields.
                
                // Validate API ID and Hash. A valid Telegram API Hash is exactly 32 hex characters.
                // If a leading zero was stripped during gradle/build config generation (making it 31 chars), auto-pad it.
                var cleanedHash = BuildConfig.TELEGRAM_API_HASH.trim().removeSurrounding("\"").removeSurrounding("'")
                if (cleanedHash.length == 33 && cleanedHash.startsWith("0")) {
                    cleanedHash = cleanedHash.substring(1)
                }
                val paddedHash = if (cleanedHash.length == 31) "0$cleanedHash" else cleanedHash
                
                val isCustomConfigValid = BuildConfig.TELEGRAM_API_ID != 0 && 
                        paddedHash.length == 32 && 
                        paddedHash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                
                if (!isCustomConfigValid && (BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty())) {
                    Timber.w("Custom Telegram API config is invalid. Falling back to official credentials.")
                }

                val apiId = if (isCustomConfigValid) BuildConfig.TELEGRAM_API_ID else 2040
                val apiHash = if (isCustomConfigValid) paddedHash else "b18441a1ff760113157c375cd7630d4c"

                client?.send(TdApi.SetTdlibParameters(
                    false, // useTestDc
                    databaseDirectory,
                    filesDirectory,
                    null, // databaseEncryptionKey
                    true, // useFileDatabase
                    true, // useChatInfoDatabase
                    true, // useMessageDatabase
                    false, // useSecretChats
                    apiId,
                    apiHash,
                    "en", // systemLanguageCode
                    "PixelMusic Instance", // deviceModel
                    android.os.Build.VERSION.RELEASE, // systemVersion
                    BuildConfig.VERSION_NAME
                ), defaultHandler)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                // UI should prompt for phone number
            }
            is TdApi.AuthorizationStateWaitCode -> {
                // UI should prompt for code
            }
            is TdApi.AuthorizationStateReady -> {
                Timber.d("Telegram Client Ready")
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                Timber.d("Logging out")
            }
            is TdApi.AuthorizationStateClosing -> {
                Timber.d("Closing")
            }
            is TdApi.AuthorizationStateClosed -> {
                Timber.d("Closed")
                client = null
                if (recreateClientAfterClose) {
                    recreateClientAfterClose = false
                    initializeClient()
                }
            }
            else -> {}
        }
    }

    fun sendPhoneNumber(phoneNumber: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings()
        clientScope.launch {
            var waitTime = 0
            while (client == null && waitTime < 100) {
                delay(50)
                waitTime++
            }
            client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings), defaultHandler)
        }
    }

    fun checkAuthenticationCode(code: String) {
        clientScope.launch {
            var waitTime = 0
            while (client == null && waitTime < 100) {
                delay(50)
                waitTime++
            }
            client?.send(TdApi.CheckAuthenticationCode(code), defaultHandler)
        }
    }
    
    fun checkAuthenticationPassword(password: String) {
        clientScope.launch {
            var waitTime = 0
            while (client == null && waitTime < 100) {
                delay(50)
                waitTime++
            }
            client?.send(TdApi.CheckAuthenticationPassword(password), defaultHandler)
        }
    }

    fun logout() {
        recreateClientAfterClose = true
        clientScope.launch {
            var waitTime = 0
            while (client == null && waitTime < 100) {
                delay(50)
                waitTime++
            }
            client?.send(TdApi.LogOut(), defaultHandler)
        }
    }

    fun closeClient(recreate: Boolean = false) {
        recreateClientAfterClose = recreate
        clientScope.launch {
            var waitTime = 0
            while (client == null && waitTime < 100) {
                delay(50)
                waitTime++
            }
            client?.send(TdApi.Close(), defaultHandler)
        }
    }

    /**
     * General purpose suspend function to send requests to TDLib
     */
    suspend fun <T : TdApi.Object> sendRequest(function: TdApi.Function<*>): T {
        ensureLibraryLoaded()
        var waitTime = 0
        while (client == null && waitTime < 100) {
            delay(50)
            waitTime++
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val localClient = client
            if (localClient != null) {
                localClient.send(function) { result ->
                    if (result is TdApi.Error) {
                        reportTdError(result)
                        continuation.resumeWith(
                            Result.failure(
                                TdlibRequestException(
                                    code = result.code,
                                    rawMessage = result.message
                                )
                            )
                        )
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resumeWith(Result.success(result as T))
                    }
                }
            } else {
                continuation.resumeWith(Result.failure(IllegalStateException("Telegram Client is not initialized")))
            }
        }
    }

    private val defaultHandler = Client.ResultHandler { result ->
        if (result is TdApi.Error) {
            reportTdError(result)
        }
    }

    private fun reportTdError(error: TdApi.Error) {
        _errors.tryEmit(error)
        Timber.e("TDLib Error: ${error.code} - ${error.message}")
    }

    /**
     * Quick check if TDLib is ready to process requests.
     */
    fun isReady(): Boolean = _authorizationState.value is TdApi.AuthorizationStateReady

    /**
     * Suspends until the TDLib client reaches AuthorizationStateReady.
     * @param timeoutMs Maximum time to wait (default 30 seconds)
     * @return true if ready, false if timed out or closed
     */
    suspend fun awaitReady(timeoutMs: Long = 30_000L): Boolean {
        // Quick check first
        if (isReady()) return true
        
        return try {
            withTimeoutOrNull(timeoutMs) {
                authorizationState.first { state ->
                    state is TdApi.AuthorizationStateReady ||
                    state is TdApi.AuthorizationStateClosed
                }
            } is TdApi.AuthorizationStateReady
        } catch (e: Exception) {
            Timber.w("awaitReady failed: ${e.message}")
            false
        }
    }
}

class TdlibRequestException(
    val code: Int,
    rawMessage: String?
) : Exception(rawMessage ?: "Unknown TDLib error")
