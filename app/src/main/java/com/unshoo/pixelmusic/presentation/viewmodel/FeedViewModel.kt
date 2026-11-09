package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.unshoo.pixelmusic.data.feed.FeedArtist
import com.unshoo.pixelmusic.data.feed.FeedData
import com.unshoo.pixelmusic.data.feed.FeedMix
import com.unshoo.pixelmusic.data.feed.GeneratedTrack
import com.unshoo.pixelmusic.data.feed.RecentTrack
import com.unshoo.pixelmusic.data.feed.YouTubeMusicTrack
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class FeedUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val launchingRadio: String? = null,
    val error: String? = null,
    val feedData: FeedData = FeedData(),
)

/**
 * Backward-compatible delegating facade.
 * FeedViewModel functionality has been unified into ExploreViewModel.
 */
class FeedViewModel(
    private val exploreViewModel: ExploreViewModel,
) : ViewModel() {

    val uiState: StateFlow<FeedUiState> = exploreViewModel.feedUiState
    val blockedReleaseIds = exploreViewModel.blockedReleaseIds
    val pureYtMusicOnly = exploreViewModel.pureYtMusicOnly
    val filterKeywords = exploreViewModel.filterKeywords

    fun blockRelease(id: String) = exploreViewModel.blockRelease(id)
    fun loadFeed() = exploreViewModel.loadData(forceRefresh = false)
    fun refresh() = exploreViewModel.refreshOnEntry(force = true)
    fun onVisible() = exploreViewModel.refreshOnEntry(force = false)
    fun dismissError() { /* Managed by ExploreViewModel */ }

    fun playTrack(track: YouTubeMusicTrack, playerViewModel: PlayerViewModel, sourceLabel: String = "Feed") =
        exploreViewModel.playTrack(track, playerViewModel, sourceLabel)

    fun shuffleTracksQueue(tracks: List<YouTubeMusicTrack>, playerViewModel: PlayerViewModel, sourceLabel: String = "Feed") =
        exploreViewModel.shuffleTracksQueue(tracks, playerViewModel, sourceLabel)

    fun playTracksQueue(tracks: List<YouTubeMusicTrack>, startIndex: Int = 0, playerViewModel: PlayerViewModel, sourceLabel: String = "Feed") =
        exploreViewModel.playTracksQueue(tracks, startIndex, playerViewModel, sourceLabel)

    fun playRecentQueue(tracks: List<RecentTrack>, startIndex: Int = 0, playerViewModel: PlayerViewModel) =
        exploreViewModel.playRecentQueue(tracks, startIndex, playerViewModel)

    fun playGeneratedQueue(tracks: List<GeneratedTrack>, startIndex: Int = 0, playerViewModel: PlayerViewModel, sourceLabel: String = "Heavy Rotation") =
        exploreViewModel.playGeneratedQueue(tracks, startIndex, playerViewModel, sourceLabel)

    fun playMix(mix: FeedMix, playerViewModel: PlayerViewModel) =
        exploreViewModel.playMix(mix, playerViewModel)

    fun playInfiniteRadio(playerViewModel: PlayerViewModel) =
        exploreViewModel.playInfiniteRadio(playerViewModel)

    fun playArtistRadio(artist: FeedArtist, playerViewModel: PlayerViewModel) =
        exploreViewModel.playArtistRadio(artist, playerViewModel)
}
