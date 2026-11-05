package com.unshoo.pixelmusic.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemBuilderTest {

    @Test
    fun shouldPreferDirectLocalFileUri_prefersDirectFileUriForLocalM4aMediaStoreItems() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isTrue()
    }

    @Test
    fun shouldPreferDirectLocalFileUri_keepsContentUriForFormatsThatAlreadySeekCorrectly() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/24",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    @Test
    fun shouldPreferDirectLocalFileUri_keepsCloudUrisUntouched() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "telegram://123/456",
            filePath = "/storage/emulated/0/Download/cached-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    @Test
    fun playbackMimeType_clearsAmbiguousLocalM4aMimeType() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(playbackMimeType).isNull()
    }

    @Test
    fun playbackMimeType_keepsNonMp4MimeTypeForLocalPlayback() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/84",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(playbackMimeType).isEqualTo("audio/flac")
    }

    @Test
    fun unwrapArtworkUri_returnsDirectHttpUrlUntouched() {
        val url = "https://lh3.googleusercontent.com/test-art=w512-h512"
        val result = MediaItemBuilder.unwrapArtworkUri(url)
        assertThat(result).isEqualTo(url)
    }

    @Test
    fun unwrapArtworkUri_unwrapsContentProviderRemoteUrl() {
        val original = "https://lh3.googleusercontent.com/sample_art"
        val wrapped = "content://com.unshoo.pixelmusic.debug.artwork/remote?url=https%3A%2F%2Flh3.googleusercontent.com%2Fsample_art"
        val result = MediaItemBuilder.unwrapArtworkUri(wrapped)
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun unwrapArtworkUri_unwrapsNestedContentProviderRemoteUrl() {
        val original = "https://lh3.googleusercontent.com/nested_art"
        val doubleWrapped = "content://com.unshoo.pixelmusic.debug.artwork/remote?url=content%3A%2F%2Fcom.unshoo.pixelmusic.debug.artwork%2Fremote%3Furl%3Dhttps%253A%252F%252Flh3.googleusercontent.com%252Fnested_art"
        val result = MediaItemBuilder.unwrapArtworkUri(doubleWrapped)
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun resolveArtworkUri_fallsBackToYoutubeThumbnailWhenArtworkMissing() {
        val result = MediaItemBuilder.resolveArtworkUri(
            rawArt = null,
            mediaId = "youtube_dQw4w9WgXcQ",
            youtubeId = null
        )
        assertThat(result).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
    }

    @Test
    fun resolveArtworkUri_prefersCleanArtOverFallback() {
        val art = "https://example.com/cover.jpg"
        val result = MediaItemBuilder.resolveArtworkUri(
            rawArt = art,
            mediaId = "youtube_dQw4w9WgXcQ",
            youtubeId = "dQw4w9WgXcQ"
        )
        assertThat(result).isEqualTo(art)
    }
}