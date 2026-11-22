package com.unshoo.pixelmusic.presentation.viewmodel

import com.google.gson.GsonBuilder
import com.unshoo.pixelmusic.data.feed.FeedData
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import unshoo.ianshulyadav.pixelmusic.innertube.models.AlbumItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.PlaylistItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import unshoo.ianshulyadav.pixelmusic.innertube.models.YTItem
import unshoo.ianshulyadav.pixelmusic.innertube.pages.ChartsPage
import unshoo.ianshulyadav.pixelmusic.innertube.pages.HomePage

internal object ExploreFeedCache {
    private const val MaxCacheBytes = 500 * 1024
    private val gson = GsonBuilder()
        .registerTypeAdapter(YTItem::class.java, YTItemTypeAdapter())
        .create()

    fun read(file: File): ExploreCacheModel? {
        if (!file.isFile || file.length() > MaxCacheBytes) return null
        return file.bufferedReader().use { gson.fromJson(it, ExploreCacheModel::class.java) }
    }

    fun write(file: File, cache: ExploreCacheModel) {
        val bytes = gson.toJson(cache).toByteArray(Charsets.UTF_8)
        if (bytes.size > MaxCacheBytes) return

        val tempFile = File.createTempFile("explore-", ".tmp", file.parentFile)
        try {
            tempFile.writeBytes(bytes)
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete()
        }
    }
}

@androidx.annotation.Keep
data class ExploreCacheModel(
    val sections: List<HomePage.Section> = emptyList(),
    val moodChips: List<HomePage.Chip> = emptyList(),
    val newReleaseAlbums: List<AlbumItem> = emptyList(),
    val albumsForYou: List<AlbumItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val charts: ChartsPage? = null,
    val continuation: String? = null,
    val timestamp: Long = 0L,
    /** Account the payload was fetched for ("anon" or "auth:<account>"); null in old caches. */
    val authKey: String? = null,
    /** The exact feed that was on screen when cached. Null in caches written by older versions. */
    val feedData: FeedData? = null,
)

private class YTItemTypeAdapter : com.google.gson.JsonSerializer<YTItem>, com.google.gson.JsonDeserializer<YTItem> {
    override fun serialize(src: YTItem, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
        val obj = com.google.gson.JsonObject()
        when (src) {
            is SongItem -> {
                obj.addProperty("type", "song")
                obj.add("data", context.serialize(src, SongItem::class.java))
            }
            is AlbumItem -> {
                obj.addProperty("type", "album")
                obj.add("data", context.serialize(src, AlbumItem::class.java))
            }
            is PlaylistItem -> {
                obj.addProperty("type", "playlist")
                obj.add("data", context.serialize(src, PlaylistItem::class.java))
            }
            is ArtistItem -> {
                obj.addProperty("type", "artist")
                obj.add("data", context.serialize(src, ArtistItem::class.java))
            }
        }
        return obj
    }

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): YTItem? {
        return try {
            val obj = json.asJsonObject
            val type = obj.get("type")?.asString ?: return null
            val targetClass = when (type) {
                "song", "SongItem" -> SongItem::class.java
                "album", "AlbumItem" -> AlbumItem::class.java
                "playlist", "PlaylistItem" -> PlaylistItem::class.java
                "artist", "ArtistItem" -> ArtistItem::class.java
                else -> null
            } ?: return null
            val data = obj.get("data") ?: obj
            context.deserialize(data, targetClass)
        } catch (_: Exception) {
            null
        }
    }
}
