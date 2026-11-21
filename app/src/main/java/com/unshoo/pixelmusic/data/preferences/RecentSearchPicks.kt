package com.unshoo.pixelmusic.data.preferences

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unshoo.pixelmusic.data.model.Artist
import com.unshoo.pixelmusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A song or artist the user opened from search results, shown under "Recent searches". */
@Immutable
data class RecentSearchPick(
    val type: Type,
    /** Song id (e.g. youtube_xxx / local id) or artist route id (channel id or local id). */
    val id: String,
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val timestamp: Long,
    // Song-only fields needed to play it again without a new search.
    val contentUri: String? = null,
    val youtubeId: String? = null,
    val durationMs: Long = 0L,
    val path: String? = null,
) {
    enum class Type { SONG, ARTIST }

    val key: String get() = "${type.name}:$id"

    fun toSong(): Song = Song.emptySong().copy(
        id = id,
        title = title,
        artist = subtitle,
        albumArtUriString = artUrl,
        contentUriString = contentUri.orEmpty(),
        youtubeId = youtubeId,
        duration = durationMs,
        path = path.orEmpty()
    )
}

/**
 * Small most-recent-first list of tapped search results (songs + artists), stored as JSON in the
 * settings DataStore so no database migration is needed. Capped at [MAX_ITEMS].
 */
object RecentSearchPicks {
    private val KEY = stringPreferencesKey("recent_search_picks")
    private const val MAX_ITEMS = 20

    fun flow(context: Context): Flow<List<RecentSearchPick>> =
        context.applicationContext.dataStore.data
            .map { decode(it[KEY]) }
            .distinctUntilChanged()

    suspend fun addSong(context: Context, song: Song) = add(
        context,
        RecentSearchPick(
            type = RecentSearchPick.Type.SONG,
            id = song.id,
            title = song.title,
            subtitle = song.displayArtist,
            artUrl = song.albumArtUriString,
            timestamp = System.currentTimeMillis(),
            contentUri = song.contentUriString,
            youtubeId = song.youtubeId,
            durationMs = song.duration,
            path = song.path
        )
    )

    suspend fun addArtist(context: Context, artist: Artist) = add(
        context,
        RecentSearchPick(
            type = RecentSearchPick.Type.ARTIST,
            id = artist.channelId ?: artist.id.toString(),
            title = artist.name,
            subtitle = "Artist",
            artUrl = artist.customImageUri ?: artist.imageUrl,
            timestamp = System.currentTimeMillis()
        )
    )

    suspend fun remove(context: Context, key: String) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[KEY] = encode(decode(prefs[KEY]).filterNot { it.key == key })
        }
    }

    suspend fun clear(context: Context) {
        context.applicationContext.dataStore.edit { it.remove(KEY) }
    }

    private suspend fun add(context: Context, pick: RecentSearchPick) {
        if (pick.title.isBlank()) return
        context.applicationContext.dataStore.edit { prefs ->
            val current = decode(prefs[KEY]).filterNot { it.key == pick.key }
            prefs[KEY] = encode((listOf(pick) + current).take(MAX_ITEMS))
        }
    }

    private fun encode(list: List<RecentSearchPick>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("type", p.type.name)
                put("id", p.id)
                put("title", p.title)
                put("subtitle", p.subtitle)
                put("art", p.artUrl ?: "")
                put("ts", p.timestamp)
                put("uri", p.contentUri ?: "")
                put("yt", p.youtubeId ?: "")
                put("dur", p.durationMs)
                put("path", p.path ?: "")
            })
        }
    }.toString()

    private fun decode(raw: String?): List<RecentSearchPick> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { RecentSearchPick.Type.valueOf(o.optString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                RecentSearchPick(
                    type = type,
                    id = o.optString("id"),
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle"),
                    artUrl = o.optString("art").ifBlank { null },
                    timestamp = o.optLong("ts"),
                    contentUri = o.optString("uri").ifBlank { null },
                    youtubeId = o.optString("yt").ifBlank { null },
                    durationMs = o.optLong("dur"),
                    path = o.optString("path").ifBlank { null }
                )
            }
        }.getOrDefault(emptyList())
    }
}
