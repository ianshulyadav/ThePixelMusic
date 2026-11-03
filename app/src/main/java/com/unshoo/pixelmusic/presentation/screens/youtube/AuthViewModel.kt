package com.unshoo.pixelmusic.presentation.screens.youtube

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.model.youtube.Cookies
import com.unshoo.pixelmusic.data.worker.SyncManager
import com.unshoo.pixelmusic.data.worker.YouTubeLibrarySyncManager
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val datastoreRepository: DatastoreRepository,
    private val syncManager: SyncManager,
    private val youTubeLibrarySyncManager: YouTubeLibrarySyncManager,
    private val listeningStatsTracker: com.unshoo.pixelmusic.presentation.viewmodel.ListeningStatsTracker
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsState())

    private val _eventsChannel = MutableSharedFlow<ScreenEvent.Out>()
    val eventFlow = _eventsChannel.asSharedFlow()

    private var hasCookie = false

    fun onPageFinished(url: String?) {
        viewModelScope.launch {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://music.youtube.com").orEmpty()
            if ((cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID")) && !_uiState.value.isLoggedIn) {
                hasCookie = true
                saveCookies(Cookies(cookies))
                // Do NOT emit LoginCompleted yet — wait for ytcfg confirmation
                // (window.ytcfg.get('LOGGED_IN') == 'true') via onYtcfgConfirmed().
                // A cookie alone does not prove the page exposed a signed-in identity;
                // multi-channel logins can sit on the profile-picker page with cookies
                // but no confirmed identity, which previously left the user in Guest mode.
            }
        }
    }

    /** Called from the WebView after evaluating window.ytcfg. Only complete login when the page itself confirms signed-in state. */
    fun onYtcfgConfirmed(loggedIn: Boolean) {
        viewModelScope.launch {
            if (loggedIn && hasCookie && !_uiState.value.isLoggedIn) {
                _uiState.update { it.copy(isLoggedIn = true) }
                _eventsChannel.emit(ScreenEvent.Out.LoginCompleted)
                launch(kotlinx.coroutines.Dispatchers.IO) { youTubeLibrarySyncManager.syncNow(force = true) }
                // Immediately pull YT Music listening history so Recently Played shows
                // local songs + authenticated YT history after login.
                listeningStatsTracker.refreshMergedYoutubeHistory()
                syncManager.incrementalSync()
            }
        }
    }

    fun onDataSyncIdFound(dataSyncId: String) {
        viewModelScope.launch {
            datastoreRepository.saveDataSyncId(dataSyncId)
            YouTube.dataSyncId = dataSyncId
        }
    }

    private fun saveCookies(cookies: Cookies) {
        viewModelScope.launch {
            datastoreRepository.saveCookies(cookies)
            val raw = cookies.toRawCookie()
            YouTube.cookie = raw
            YouTube.useLoginForBrowse = raw.contains("SAPISID=") || raw.contains("__Secure-3PAPISID=")
        }
    }

    sealed interface ScreenEvent {
        sealed class Out {
            object LoginCompleted : Out()
        }
    }
}
