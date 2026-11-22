package com.unshoo.pixelmusic.data.announcement

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unshoo.pixelmusic.data.github.AppAnnouncement
import com.unshoo.pixelmusic.data.github.BirthdaySiteConfig
import com.unshoo.pixelmusic.data.github.BirthdaySurpriseRemoteConfig
import com.unshoo.pixelmusic.data.github.BirthdaySurpriseService
import com.unshoo.pixelmusic.data.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Everything the team controls from www.pixelmusic.me/wishes/admin.html: birthday mode/date and
 * app announcements. One cached copy of `GET /api/wishes?config=1` is shared by the birthday popup,
 * the announcement popup, the announcements inbox and the background notification check.
 */
@Singleton
class AnnouncementsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: BirthdaySurpriseService,
) {
    /** Last saved website config (no network). */
    val cachedConfig: Flow<BirthdaySiteConfig> = context.dataStore.data
        .map { BirthdaySiteConfig.fromJson(it[SITE_JSON_KEY]) ?: BirthdaySiteConfig() }
        .distinctUntilChanged()

    /**
     * Publish time of the newest announcement the user has read (opened the inbox or saw it
     * full screen). Anything published later counts as unread.
     */
    val readAt: Flow<Long> = context.dataStore.data
        .map { it[READ_AT_KEY] ?: 0L }
        .distinctUntilChanged()

    /** True when an announcement newer than [readAt] exists (unread dot on the bookmark button). */
    val hasUnread: Flow<Boolean> = context.dataStore.data
        .map { prefs ->
            val newest = BirthdaySiteConfig.fromJson(prefs[SITE_JSON_KEY])?.announcement
            newest != null && newest.publishedAt > (prefs[READ_AT_KEY] ?: 0L)
        }
        .distinctUntilChanged()

    private val _inboxOpen = MutableStateFlow(false)
    /** Whether the What's new page is open (from the bookmark button or a notification tap). */
    val inboxOpen: StateFlow<Boolean> = _inboxOpen.asStateFlow()
    fun openInbox() { _inboxOpen.value = true }
    fun closeInbox() { _inboxOpen.value = false }

    /**
     * The website config, re-downloaded when the saved copy is older than [maxAgeMillis].
     * Falls back to the saved copy (or defaults) when offline.
     */
    suspend fun siteConfig(maxAgeMillis: Long = CACHE_MILLIS): BirthdaySiteConfig {
        val prefs = context.dataStore.data.first()
        val cached = BirthdaySiteConfig.fromJson(prefs[SITE_JSON_KEY])
        val fetchedAt = prefs[FETCHED_AT_KEY] ?: 0L
        if (cached != null && System.currentTimeMillis() - fetchedAt < maxAgeMillis) return cached
        val raw = service.fetchSiteConfigJson(BirthdaySurpriseRemoteConfig.DEFAULT_WISH_ENDPOINT)
        if (raw != null) {
            context.dataStore.edit {
                it[SITE_JSON_KEY] = raw
                it[FETCHED_AT_KEY] = System.currentTimeMillis()
            }
            return BirthdaySiteConfig.fromJson(raw) ?: BirthdaySiteConfig()
        }
        return cached ?: BirthdaySiteConfig()
    }

    /** Marks everything in the inbox as read (clears the unread dots). */
    suspend fun markAllRead() {
        val newest = context.dataStore.data.first()[SITE_JSON_KEY]
            ?.let { BirthdaySiteConfig.fromJson(it)?.announcement } ?: return
        markReadUpTo(newest.publishedAt)
    }

    private suspend fun markReadUpTo(publishedAt: Long) {
        context.dataStore.edit { it[READ_AT_KEY] = maxOf(it[READ_AT_KEY] ?: 0L, publishedAt) }
    }

    /**
     * The newest announcement to show full screen now: within its "show until" date, unread and
     * not shown before. Marks it as shown and read so it appears only once.
     */
    suspend fun takePopupAnnouncement(today: LocalDate = LocalDate.now()): AppAnnouncement? {
        val announcement = siteConfig().announcement ?: return null
        if (!announcement.isVisibleOn(today)) return null
        val prefs = context.dataStore.data.first()
        if (announcement.id == prefs[POPUP_SEEN_ID_KEY]) return null
        if (announcement.publishedAt <= (prefs[READ_AT_KEY] ?: 0L)) return null
        context.dataStore.edit { it[POPUP_SEEN_ID_KEY] = announcement.id }
        markReadUpTo(announcement.publishedAt)
        return announcement
    }

    /**
     * The newest announcement to send as a system notification: unread and not notified before.
     * Marks it as notified. [force] skips those checks (Developer Options test button).
     */
    suspend fun takeAnnouncementToNotify(force: Boolean = false): AppAnnouncement? {
        val announcement = siteConfig(maxAgeMillis = 0L).announcement ?: return null
        val prefs = context.dataStore.data.first()
        val alreadyHandled = announcement.id == prefs[NOTIFIED_ID_KEY] ||
            announcement.publishedAt <= (prefs[READ_AT_KEY] ?: 0L)
        if (alreadyHandled && !force) return null
        context.dataStore.edit { it[NOTIFIED_ID_KEY] = announcement.id }
        return announcement
    }

    companion object {
        // Short, so a new announcement shows on the next app open; responses are CDN-cached server-side.
        const val CACHE_MILLIS = 5 * 60 * 1000L

        val SITE_JSON_KEY: Preferences.Key<String> = stringPreferencesKey("site_config_json")
        val FETCHED_AT_KEY: Preferences.Key<Long> = longPreferencesKey("site_config_fetched_at")
        private val POPUP_SEEN_ID_KEY: Preferences.Key<String> = stringPreferencesKey("seen_announcement_id")
        private val READ_AT_KEY: Preferences.Key<Long> = longPreferencesKey("announcements_read_at")
        private val NOTIFIED_ID_KEY: Preferences.Key<String> = stringPreferencesKey("notified_announcement_id")
    }
}
