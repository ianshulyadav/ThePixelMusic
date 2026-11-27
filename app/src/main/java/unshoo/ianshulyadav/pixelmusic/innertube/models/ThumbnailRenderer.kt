/*
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/ianshulyadav
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package unshoo.ianshulyadav.pixelmusic.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ThumbnailRenderer(
    val musicThumbnailRenderer: MusicThumbnailRenderer? = null,
    val musicAnimatedThumbnailRenderer: MusicAnimatedThumbnailRenderer? = null,
    val croppedSquareThumbnailRenderer: MusicThumbnailRenderer? = null,
) {
    fun getThumbnailUrl(): String? =
        musicThumbnailRenderer?.getThumbnailUrl()
            ?: croppedSquareThumbnailRenderer?.getThumbnailUrl()
            ?: musicAnimatedThumbnailRenderer?.backupRenderer?.getThumbnailUrl()
            ?: musicAnimatedThumbnailRenderer?.animatedThumbnail?.thumbnails?.lastOrNull()?.normalizedUrl

    @Serializable
    data class MusicThumbnailRenderer(
        val thumbnail: Thumbnails = Thumbnails(),
        val thumbnailCrop: String? = null,
        val thumbnailScale: String? = null,
    ) {
        fun getThumbnailUrl() = thumbnail.thumbnails.lastOrNull()?.normalizedUrl
    }

    @Serializable
    data class MusicAnimatedThumbnailRenderer(
        val animatedThumbnail: Thumbnails = Thumbnails(),
        val backupRenderer: MusicThumbnailRenderer = MusicThumbnailRenderer(),
    )
}
