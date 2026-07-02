package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.toSongs
import com.unshoo.pixelmusic.data.model.ArtistRef
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository.ArtistPlaybackSummary
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository.GenrePlaybackSummary
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository.SongPlaybackSummary
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository.AlbumPlaybackSummary
import com.unshoo.pixelmusic.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.unshoo.pixelmusic.data.stats.StatsTimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RecapUiState(
    val isLoading: Boolean = true,
    val selectedPeriod: RecapPeriod = RecapPeriod.LAST_30_DAYS,
    val totalListeningMinutes: Long = 0L,
    val totalUniqueSongs: Int = 0,
    val totalUniqueArtists: Int = 0,
    val topSongs: List<SongPlaybackSummary> = emptyList(),
    val topArtists: List<ArtistPlaybackSummary> = emptyList(),
    val topGenres: List<GenrePlaybackSummary> = emptyList(),
    val topAlbums: List<AlbumPlaybackSummary> = emptyList(),
    val peakListeningTimeOfDay: String = "Evening", // "Morning", "Afternoon", "Evening", "Night Owl"
    val listeningPersonality: ListeningPersonality = ListeningPersonality.EXPLORER,
    val dominantCoverUrl: String? = null,
    val activeDays: Int = 0,
    val longestStreakDays: Int = 0,
    val error: String? = null
)

enum class RecapPeriod(val label: String, val statsRange: StatsTimeRange) {
    LAST_30_DAYS("Last 30 Days", StatsTimeRange.MONTH),
    THIS_YEAR("This Year", StatsTimeRange.YEAR),
    ALL_TIME("All Time", StatsTimeRange.ALL)
}

enum class ListeningPersonality(val title: String, val subtitle: String, val emoji: String) {
    LOYALIST("The Loyalist", "You play your top favorites on endless heavy rotation.", "🔁"),
    EXPLORER("The Explorer", "Always hunting for new sounds, rarely repeating.", "🧭"),
    NIGHT_OWL("The Night Owl", "Your headphones come alive after sunset.", "🌙"),
    GENRE_HOPPER("The Genre Hopper", "From Classical to Synthwave—your taste knows no limits.", "🎲")
}

@HiltViewModel
class RecapViewModel @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecapUiState())
    val uiState: StateFlow<RecapUiState> = _uiState.asStateFlow()

    init {
        loadRecap(RecapPeriod.LAST_30_DAYS)
    }

    fun selectPeriod(period: RecapPeriod) {
        _uiState.update { it.copy(selectedPeriod = period, isLoading = true, error = null) }
        loadRecap(period)
    }

    private fun loadRecap(period: RecapPeriod) {
        viewModelScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    val rawSongs = musicDao.getAllSongsList()
                    val songs = rawSongs.toSongs()
                    val summary = playbackStatsRepository.loadSummary(period.statsRange, songs)
                    
                    val totalMinutes = summary.totalDurationMs / 1000 / 60
                    val uniqueSongsCount = summary.uniqueSongs
                    val uniqueArtistsCount = summary.topArtists.size
                    
                    val personality = calculatePersonality(summary)
                    val peakTime = calculatePeakTimeOfDay(summary)
                    
                    val dominantArt = summary.topSongs.firstOrNull()?.albumArtUri
                        ?: summary.topAlbums.firstOrNull()?.albumArtUri

                    RecapUiState(
                        isLoading = false,
                        selectedPeriod = period,
                        totalListeningMinutes = totalMinutes,
                        totalUniqueSongs = uniqueSongsCount,
                        totalUniqueArtists = uniqueArtistsCount,
                        topSongs = summary.topSongs,
                        topArtists = summary.topArtists,
                        topGenres = summary.topGenres,
                        topAlbums = summary.topAlbums,
                        peakListeningTimeOfDay = peakTime,
                        listeningPersonality = personality,
                        dominantCoverUrl = dominantArt,
                        activeDays = summary.activeDays,
                        longestStreakDays = summary.longestStreakDays,
                        error = null
                    )
                }
                _uiState.value = state
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Unknown error") }
            }
        }
    }

    private fun calculatePersonality(summary: PlaybackStatsSummary): ListeningPersonality {
        if (summary.totalDurationMs <= 0) return ListeningPersonality.EXPLORER

        val topSongShare = summary.topSongs.take(3).sumOf { it.totalDurationMs }.toFloat() / summary.totalDurationMs
        val buckets = summary.dayListeningDistribution?.buckets ?: emptyList()
        val nightDuration = buckets.filter { it.startMinute >= 22 * 60 || it.endMinuteExclusive <= 6 * 60 }.sumOf { it.totalDurationMs }
        val nightRatio = nightDuration.toFloat() / summary.totalDurationMs

        return when {
            topSongShare > 0.35f -> ListeningPersonality.LOYALIST
            summary.topGenres.size > 8 -> ListeningPersonality.GENRE_HOPPER
            nightRatio > 0.40f -> ListeningPersonality.NIGHT_OWL
            else -> ListeningPersonality.EXPLORER
        }
    }

    private fun calculatePeakTimeOfDay(summary: PlaybackStatsSummary): String {
        val buckets = summary.dayListeningDistribution?.buckets ?: emptyList()
        if (buckets.isEmpty()) return "Evening"

        // Divide day into 4 intervals:
        // Morning: 6:00 - 12:00 (360 - 720 mins)
        // Afternoon: 12:00 - 17:00 (720 - 1020 mins)
        // Evening: 17:00 - 22:00 (1020 - 1320 mins)
        // Late Night: 22:00 - 6:00 (1320 - 360 mins)
        var morningDur = 0L
        var afternoonDur = 0L
        var eveningDur = 0L
        var nightDur = 0L

        for (bucket in buckets) {
            val start = bucket.startMinute
            if (start in 360 until 720) {
                morningDur += bucket.totalDurationMs
            } else if (start in 720 until 1020) {
                afternoonDur += bucket.totalDurationMs
            } else if (start in 1020 until 1320) {
                eveningDur += bucket.totalDurationMs
            } else {
                nightDur += bucket.totalDurationMs
            }
        }

        val max = maxOf(morningDur, afternoonDur, eveningDur, nightDur)
        return when (max) {
            morningDur -> "Morning"
            afternoonDur -> "Afternoon"
            eveningDur -> "Evening"
            else -> "Night Owl"
        }
    }

    fun playTopTracks(playerViewModel: com.unshoo.pixelmusic.presentation.viewmodel.PlayerViewModel) {
        viewModelScope.launch {
            val songsToPlay = getSongsList()
            if (songsToPlay.isNotEmpty()) {
                playerViewModel.playSongs(
                    songsToPlay = songsToPlay,
                    startSong = songsToPlay.first(),
                    queueName = "My ${_uiState.value.selectedPeriod.label} Recap"
                )
            }
        }
    }

    fun saveRecapAsPlaylist(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val songIds = _uiState.value.topSongs.map { it.songId }
                if (songIds.isNotEmpty()) {
                    playlistPreferencesRepository.createPlaylist(
                        name = "My ${_uiState.value.selectedPeriod.label} Recap",
                        songIds = songIds,
                        isAiGenerated = true
                    )
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            } catch (e: Exception) {
                onComplete(false)
            }
        }
    }

    private suspend fun getSongsList(): List<Song> = withContext(Dispatchers.IO) {
        val rawSongs = musicDao.getAllSongsList()
        val songsMap = rawSongs.toSongs().associateBy { it.id }
        _uiState.value.topSongs.map { entry ->
            songsMap[entry.songId] ?: Song(
                id = entry.songId,
                title = entry.title,
                artist = entry.artist,
                artistId = 0L,
                album = "",
                albumId = 0L,
                path = "",
                contentUriString = "",
                albumArtUriString = entry.albumArtUri,
                duration = entry.totalDurationMs,
                mimeType = null,
                bitrate = null,
                sampleRate = null,
                youtubeId = entry.songId.takeIf { !it.startsWith("-") && it.length == 11 }
            )
        }
    }
}
