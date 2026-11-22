package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.announcement.AnnouncementsRepository
import com.unshoo.pixelmusic.data.github.AppAnnouncement
import com.unshoo.pixelmusic.data.github.BirthdaySiteConfig
import com.unshoo.pixelmusic.data.github.BirthdaySurpriseRemoteConfig
import com.unshoo.pixelmusic.data.github.BirthdaySurpriseService
import com.unshoo.pixelmusic.data.github.BirthdayWishResult
import com.unshoo.pixelmusic.data.preferences.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface WishSendState {
    data object Idle : WishSendState
    data object Sending : WishSendState
    data object Sent : WishSendState
    /** [reason] is the server's rejection code (`not_kind`, `no_links`, `slow_down`), if any. */
    data class Error(val notConfigured: Boolean, val reason: String? = null) : WishSendState
}

object BirthdaySurpriseSchedule {
    /** The date (YYYY-MM-DD) the popup was last shown, so it appears at most once a day. */
    val SHOWN_DATE_KEY: Preferences.Key<String> = stringPreferencesKey("birthday_surprise_shown_date")
}

/**
 * Birthday popup + website-driven announcements (popup and inbox). Mode, date and
 * announcements are set on www.pixelmusic.me/wishes/admin.html and shared through
 * [AnnouncementsRepository].
 */
@HiltViewModel
class BirthdaySurpriseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: BirthdaySurpriseService,
    private val announcements: AnnouncementsRepository,
) : ViewModel() {

    private val _config = MutableStateFlow(BirthdaySurpriseRemoteConfig())
    val config: StateFlow<BirthdaySurpriseRemoteConfig> = _config.asStateFlow()

    private val _wishState = MutableStateFlow<WishSendState>(WishSendState.Idle)
    val wishState: StateFlow<WishSendState> = _wishState.asStateFlow()

    private var configLoaded = false

    private suspend fun loadConfig(): BirthdaySurpriseRemoteConfig {
        if (!configLoaded) {
            _config.value = service.fetchConfig()
            configLoaded = true
        }
        return _config.value
    }

    /** Birthday mode, celebration date and announcements from the website (last saved copy). */
    val siteConfig: StateFlow<BirthdaySiteConfig> = announcements.cachedConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, BirthdaySiteConfig())

    /** Unread dot for the announcements (bookmark) button. */
    val hasUnreadAnnouncements: StateFlow<Boolean> = announcements.hasUnread
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Publish time of the newest read announcement; newer ones show as unread. */
    val announcementsReadAt: StateFlow<Long> = announcements.readAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Whether the announcements inbox sheet is open. */
    val inboxOpen: StateFlow<Boolean> = announcements.inboxOpen

    fun openInbox() = announcements.openInbox()

    fun closeInbox() {
        announcements.closeInbox()
        viewModelScope.launch { announcements.markAllRead() }
    }

    /** Re-downloads the website config when the inbox opens, so it shows the latest list. */
    fun refreshInbox() {
        viewModelScope.launch {
            announcements.siteConfig(maxAgeMillis = 0L)
            announcements.markAllRead()
        }
    }

    private val _pendingAnnouncement = MutableStateFlow<AppAnnouncement?>(null)
    /** Announcement popup waiting to be shown; kept here so it survives rotation. */
    val pendingAnnouncement: StateFlow<AppAnnouncement?> = _pendingAnnouncement.asStateFlow()

    /**
     * Queues the newest announcement as a popup if this user hasn't seen it yet
     * (first launch after it was published).
     */
    suspend fun consumeAnnouncement(today: LocalDate = LocalDate.now()): AppAnnouncement? {
        val announcement = announcements.takePopupAnnouncement(today) ?: return null
        _pendingAnnouncement.value = announcement
        return announcement
    }

    /** Fetches the latest website config (called once per app start). */
    suspend fun refreshSiteConfig() {
        announcements.siteConfig(maxAgeMillis = 0L)
    }

    fun dismissAnnouncement() {
        _pendingAnnouncement.value = null
    }

    /** Prefetches remote config (endpoint, page URL, celebration date) when the dialog opens. */
    fun prepare() {
        viewModelScope.launch { loadConfig() }
        viewModelScope.launch { announcements.siteConfig() }
    }

    /**
     * True when birthday mode is active for [today] (set on the website: "on", or "auto" and
     * today is the celebration date), the popup hasn't been shown today, and the GitHub kill
     * switch isn't off. Marks today as shown.
     */
    suspend fun consumeScheduledShow(
        today: LocalDate = LocalDate.now(),
        ignoreShownToday: Boolean = false,
    ): Boolean {
        if (!announcements.siteConfig().isActiveOn(today)) return false
        val shownDate = context.dataStore.data.first()[BirthdaySurpriseSchedule.SHOWN_DATE_KEY]
        if (shownDate == today.toString() && !ignoreShownToday) return false
        if (!loadConfig().enabled) return false
        context.dataStore.edit { it[BirthdaySurpriseSchedule.SHOWN_DATE_KEY] = today.toString() }
        return true
    }

    fun sendWish(name: String, message: String) {
        if (message.isBlank() || _wishState.value == WishSendState.Sending) return
        _wishState.value = WishSendState.Sending
        viewModelScope.launch {
            val endpoint = loadConfig().wishEndpoint
            _wishState.value = when (val result = service.sendWish(endpoint, name, message)) {
                BirthdayWishResult.Sent -> WishSendState.Sent
                BirthdayWishResult.NotConfigured -> WishSendState.Error(notConfigured = true)
                is BirthdayWishResult.Rejected -> WishSendState.Error(notConfigured = false, reason = result.reason)
                is BirthdayWishResult.Failed -> WishSendState.Error(notConfigured = false)
            }
        }
    }

    fun resetWishState() {
        _wishState.update { if (it is WishSendState.Sending) it else WishSendState.Idle }
    }
}
