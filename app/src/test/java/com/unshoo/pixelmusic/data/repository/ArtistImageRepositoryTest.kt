package com.unshoo.pixelmusic.data.repository

import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.model.Artist
import com.unshoo.pixelmusic.data.network.deezer.DeezerApiService
import com.unshoo.pixelmusic.data.network.deezer.DeezerArtist
import com.unshoo.pixelmusic.data.network.deezer.DeezerSearchResponse
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistImageRepositoryTest {

    @Test
    fun `calculateCustomImageSampleSize keeps small bitmaps at full resolution`() {
        assertEquals(1, ArtistImageRepository.calculateCustomImageSampleSize(1024, 1024))
    }

    @Test
    fun `calculateCustomImageSampleSize aggressively downsamples oversized inputs`() {
        val sampleSize = ArtistImageRepository.calculateCustomImageSampleSize(12000, 8000)

        assertTrue(sampleSize >= 4)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `cancelled prefetch does not mark artist as failed for the session`() = runTest {
        val deezerApiService = mockk<DeezerApiService>()
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(deezerApiService, musicDao).apply {
            youtubeArtistLookup = { null }
        }
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val searchAttempts = AtomicInteger(0)
        val rawUrl = "https://cdn-images.dzcdn.net/images/artist/250x250-000000-80-0-0.jpg"
        val upgradedUrl = "https://cdn-images.dzcdn.net/images/artist/1000x1000-000000-80-0-0.jpg"

        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns null
        coEvery { musicDao.getArtistImageUrlByNormalizedName("Artist Name") } returns null
        coJustRun { musicDao.updateArtistImageUrl(42L, any()) }
        coEvery { deezerApiService.searchArtist("Artist Name", any()) } coAnswers {
            when (searchAttempts.incrementAndGet()) {
                1 -> {
                    firstAttemptStarted.complete(Unit)
                    awaitCancellation()
                }

                else -> DeezerSearchResponse(
                    data = listOf(
                        DeezerArtist(
                            id = 7L,
                            name = "Artist Name",
                            pictureBig = rawUrl,
                            fanCount = 500
                        )
                    )
                )
            }
        }

        val prefetchJob = launch {
            repository.prefetchArtistImages(listOf(42L to "Artist Name"))
        }
        firstAttemptStarted.await()
        prefetchJob.cancel()
        prefetchJob.join()

        val imageUrl = repository.getArtistImageUrl("Artist Name", 42L)

        assertEquals(upgradedUrl, imageUrl)
        assertEquals(2, searchAttempts.get())
        coVerify(exactly = 1) { musicDao.updateArtistImageUrl(42L, upgradedUrl) }
    }

    @Test
    fun `selects exact normalized match with highest fanCount instead of lower-fan poster result`() = runTest {
        val deezerApiService = mockk<DeezerApiService>()
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(deezerApiService, musicDao).apply {
            youtubeArtistLookup = { null }
        }
        val wrongPosterUrl =
            "https://cdn-images.dzcdn.net/images/artist/e6bfa2ee147854dc35fdc5dd4950a084/1000x1000-000000-80-0-0.jpg"
        val realArtistUrl =
            "https://cdn-images.dzcdn.net/images/artist/bd34315ef977a62a9e28c1ab19bb8ac4/1000x1000-000000-80-0-0.jpg"

        coEvery { musicDao.getArtistIdByNormalizedName("A. R. Rahman") } returns 101L
        coEvery { musicDao.getArtistImageUrl(101L) } returns null
        coEvery { musicDao.getArtistImageUrlByNormalizedName("A. R. Rahman") } returns null
        coJustRun { musicDao.updateArtistImageUrl(101L, any()) }
        coEvery { deezerApiService.searchArtist("A. R. Rahman", any()) } returns DeezerSearchResponse(
            data = listOf(
                DeezerArtist(
                    id = 253620L,
                    name = "A. R. Rahman",
                    pictureXl = wrongPosterUrl,
                    fanCount = 10495
                ),
                DeezerArtist(
                    id = 491L,
                    name = "A.R. Rahman",
                    pictureXl = realArtistUrl,
                    fanCount = 284950
                ),
                DeezerArtist(
                    id = 173750L,
                    name = "A.R.Rahman",
                    pictureXl = "https://cdn-images.dzcdn.net/images/artist//1000x1000-000000-80-0-0.jpg",
                    fanCount = 6385
                )
            )
        )

        val resolved = repository.getArtistImageUrl("A. R. Rahman", 101L)

        assertEquals(realArtistUrl, resolved)
        coVerify(exactly = 1) { musicDao.updateArtistImageUrl(101L, realArtistUrl) }
    }

    @Test
    fun `rejects empty-hash Deezer URLs and low-fan stub matches and falls back to YouTube channel art`() = runTest {
        val deezerApiService = mockk<DeezerApiService>()
        val musicDao = mockk<MusicDao>()
        val ytPortrait = "https://yt3.ggpht.com/artist_portrait=w544-h544-l90-rj"
        val repository = ArtistImageRepository(deezerApiService, musicDao).apply {
            youtubeArtistLookup = { artist -> if (artist == "A.M. Turaz") ytPortrait else null }
        }

        // Stub/poisoned blank-hash cached URL in DB should be cleared and refetched.
        coEvery { musicDao.getArtistIdByNormalizedName("A.M. Turaz") } returns 202L
        coEvery { musicDao.getArtistImageUrl(202L) } returns
            "https://cdn-images.dzcdn.net/images/artist//1000x1000-000000-80-0-0.jpg"
        coJustRun { musicDao.clearArtistImageUrl(202L) }
        coEvery { musicDao.getArtistImageUrlByNormalizedName("A.M. Turaz") } returns null
        coJustRun { musicDao.updateArtistImageUrl(202L, any()) }
        coEvery { deezerApiService.searchArtist("A.M. Turaz", any()) } returns DeezerSearchResponse(
            data = listOf(
                DeezerArtist(
                    id = 10575825L,
                    name = "A.M. Turaz",
                    pictureXl = "https://cdn-images.dzcdn.net/images/artist/4c5b766c35ed42b0f6a0d0bbec1321e1/1000x1000-000000-80-0-0.jpg",
                    fanCount = 7
                )
            )
        )

        val resolved = repository.getArtistImageUrl("A.M. Turaz", 202L)

        assertEquals(
            "https://yt3.ggpht.com/artist_portrait=w1024-h1024-l90-rj",
            resolved
        )
        coVerify(exactly = 1) { musicDao.clearArtistImageUrl(202L) }
        coVerify(exactly = 1) {
            musicDao.updateArtistImageUrl(202L, "https://yt3.ggpht.com/artist_portrait=w1024-h1024-l90-rj")
        }
    }

    @Test
    fun `Artist effectiveImageUrl ignores blank-hash Deezer placeholder URLs`() {
        val broken = Artist(
            id = 1L,
            name = "Aahaana",
            songCount = 1,
            imageUrl = "https://cdn-images.dzcdn.net/images/artist//1000x1000-000000-80-0-0.jpg"
        )
        assertNull(broken.effectiveImageUrl)

        val validCustom = broken.copy(customImageUri = "/data/user/0/app/files/artist_art_1.jpg")
        assertEquals("/data/user/0/app/files/artist_art_1.jpg", validCustom.effectiveImageUrl)
    }

    @Test
    fun `selectBestYoutubeArtistThumbnail matches middle-name channel titles for multi-word queries only`() {
        val amitChannel = ArtistItem(
            id = "UC19pnd-F7tw6vDhkmEiAkgA",
            title = "Amit Shankar Trivedi",
            thumbnail = "https://yt3.googleusercontent.com/amit=w120-h120-p-l90-rj",
            shuffleEndpoint = null,
            radioEndpoint = null
        )
        val unrelatedAmit = ArtistItem(
            id = "UCRQmZ4lsrzXAyvKRV1DSMYA",
            title = "Amitabh Bhattacharya",
            thumbnail = "https://yt3.googleusercontent.com/amitabh=w120-h120-l90-rj",
            shuffleEndpoint = null,
            radioEndpoint = null
        )
        assertEquals(
            "https://yt3.googleusercontent.com/amit=w120-h120-p-l90-rj",
            ArtistImageRepository.selectBestYoutubeArtistThumbnail(
                "AMIT TRIVEDI",
                listOf(unrelatedAmit, amitChannel)
            )
        )

        val singleWordPartial = ArtistItem(
            id = "UC_other",
            title = "Aaghaz Live Band",
            thumbnail = "https://yt3.googleusercontent.com/other=w120-h120-l90-rj",
            shuffleEndpoint = null,
            radioEndpoint = null
        )
        assertFalse(ArtistImageRepository.matchesArtistTokensInOrder("Aaghaz", "Aaghaz Live Band"))
        assertNull(
            ArtistImageRepository.selectBestYoutubeArtistThumbnail(
                "Aaghaz",
                listOf(singleWordPartial)
            )
        )
    }
}
