package com.unshoo.pixelmusic.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Sections that can appear on the Home screen, in their default order. */
enum class HomeSectionId(val storageKey: String) {
    QUICK_PICKS("quick_picks"),
    YOUR_MIX("your_mix"),
    DAILY_MIX("daily_mix"),
    RECENTLY_PLAYED("recently_played"),
    RECENTLY_ADDED("recently_added"),
    ARTIST_RELEASES("artist_releases"),
    MOST_LISTENED("most_listened"),
    LISTENING_STATS("listening_stats");

    companion object {
        fun fromKey(key: String): HomeSectionId? = entries.firstOrNull { it.storageKey == key }

        /** Shown on a fresh install (Recently added is opt-in, like the reference design). */
        val DEFAULT_ORDER: List<HomeSectionId> = listOf(
            QUICK_PICKS, YOUR_MIX, DAILY_MIX, RECENTLY_PLAYED, ARTIST_RELEASES, MOST_LISTENED, LISTENING_STATS
        )
    }
}

/** Time window used by the "Most listened" card. */
enum class MostListenedRange(val storageKey: String) {
    TODAY("today"), WEEK("week"), MONTH("month"), YEAR("year"), ALL_TIME("all");

    companion object {
        fun fromKey(key: String?): MostListenedRange = entries.firstOrNull { it.storageKey == key } ?: WEEK
    }
}

/** Which list the "Most listened" card opens on. */
enum class MostListenedKind(val storageKey: String) {
    SONGS("songs"), ALBUMS("albums"), ARTISTS("artists");

    companion object {
        fun fromKey(key: String?): MostListenedKind = entries.firstOrNull { it.storageKey == key } ?: SONGS
    }
}

data class HomeLayoutConfig(
    val sections: List<HomeSectionId> = HomeSectionId.DEFAULT_ORDER,
    val mostListenedRange: MostListenedRange = MostListenedRange.WEEK,
    val mostListenedKind: MostListenedKind = MostListenedKind.SONGS,
    /** Show the greeting line at the top of Home. */
    val showGreeting: Boolean = true,
    /** User's own greeting; blank = the automatic time-of-day greeting. "{name}" = account name. */
    val customGreeting: String = ""
)

/**
 * Home screen layout ("Customize Home"): which sections are shown, their order, and the
 * per-section options. Stored in the shared settings DataStore.
 */
object HomeLayoutPreferences {
    private val SECTIONS = stringPreferencesKey("home_layout_sections")
    private val MOST_LISTENED_RANGE = stringPreferencesKey("home_most_listened_range")
    private val MOST_LISTENED_KIND = stringPreferencesKey("home_most_listened_kind")
    private val SHOW_GREETING = androidx.datastore.preferences.core.booleanPreferencesKey("home_show_greeting")
    private val CUSTOM_GREETING = stringPreferencesKey("home_custom_greeting")

    fun configFlow(context: Context): Flow<HomeLayoutConfig> =
        context.applicationContext.dataStore.data.map { prefs ->
            val raw = prefs[SECTIONS]
            val sections = if (raw == null) {
                HomeSectionId.DEFAULT_ORDER
            } else {
                raw.split(',').mapNotNull { HomeSectionId.fromKey(it.trim()) }.distinct()
            }
            HomeLayoutConfig(
                sections = sections,
                mostListenedRange = MostListenedRange.fromKey(prefs[MOST_LISTENED_RANGE]),
                mostListenedKind = MostListenedKind.fromKey(prefs[MOST_LISTENED_KIND]),
                showGreeting = prefs[SHOW_GREETING] ?: true,
                customGreeting = prefs[CUSTOM_GREETING].orEmpty()
            )
        }.distinctUntilChanged()

    suspend fun setSections(context: Context, sections: List<HomeSectionId>) {
        context.applicationContext.dataStore.edit {
            it[SECTIONS] = sections.distinct().joinToString(",") { s -> s.storageKey }
        }
    }

    suspend fun setMostListenedRange(context: Context, range: MostListenedRange) {
        context.applicationContext.dataStore.edit { it[MOST_LISTENED_RANGE] = range.storageKey }
    }

    suspend fun setMostListenedKind(context: Context, kind: MostListenedKind) {
        context.applicationContext.dataStore.edit { it[MOST_LISTENED_KIND] = kind.storageKey }
    }

    suspend fun setShowGreeting(context: Context, show: Boolean) {
        context.applicationContext.dataStore.edit { it[SHOW_GREETING] = show }
    }

    suspend fun setCustomGreeting(context: Context, text: String) {
        context.applicationContext.dataStore.edit { it[CUSTOM_GREETING] = text.take(80) }
    }

    suspend fun reset(context: Context) {
        context.applicationContext.dataStore.edit {
            it.remove(SECTIONS)
            it.remove(MOST_LISTENED_RANGE)
            it.remove(MOST_LISTENED_KIND)
        }
    }
}
