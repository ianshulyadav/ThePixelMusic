package com.unshoo.pixelmusic.presentation.screens.youtube

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.remote.youtube.DatastoreRepository
import com.unshoo.pixelmusic.data.model.youtube.Cookies
import com.unshoo.pixelmusic.data.worker.YouTubeLibrarySyncManager
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val datastoreRepository: DatastoreRepository,
    private val youTubeLibrarySyncManager: YouTubeLibrarySyncManager,
    private val listeningStatsTracker: com.unshoo.pixelmusic.presentation.viewmodel.ListeningStatsTracker
) : ViewModel() {
    private val events = Channel<ScreenEvent.Out>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()
    private var loginInProgress = false

    fun completeLogin(dataSyncId: String) {
        if (loginInProgress) return
        val rawCookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
        val session = YouTube.authState.copy(
            cookie = rawCookie,
            dataSyncId = dataSyncId,
            visitorData = null,
        ).normalized()
        if (!session.hasLoginCookie) return
        loginInProgress = true
        viewModelScope.launch {
            try {
                datastoreRepository.saveYouTubeSession(Cookies(rawCookie), session.dataSyncId.orEmpty())
            } catch (e: IOException) {
                Timber.w(e, "Could not save YouTube Music session")
                loginInProgress = false
                events.send(ScreenEvent.Out.LoginFailed)
                return@launch
            }
            YouTube.authState = session
            YouTube.useLoginForBrowse = true
            youTubeLibrarySyncManager.syncAfterLogin()
            listeningStatsTracker.refreshMergedYoutubeHistory()
            events.send(ScreenEvent.Out.LoginCompleted)
        }
    }

    sealed interface ScreenEvent {
        sealed class Out {
            object LoginCompleted : Out()
            object LoginFailed : Out()
        }
    }
}
