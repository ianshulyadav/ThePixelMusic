package com.unshoo.pixelmusic.presentation.screens.explore

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unshoo.pixelmusic.data.model.Genre
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.components.explore.ExploreAlbumsRow
import com.unshoo.pixelmusic.presentation.components.explore.ExploreChipsRow
import com.unshoo.pixelmusic.presentation.components.explore.ExploreCircularWavyProgressIndicator
import com.unshoo.pixelmusic.presentation.components.explore.ExploreGenreChips
import com.unshoo.pixelmusic.presentation.components.explore.ExploreHeroCarouselUiModels
import com.unshoo.pixelmusic.presentation.components.explore.ExploreLinearWavyProgressIndicator
import com.unshoo.pixelmusic.presentation.components.explore.ExploreQuickPicksUiModels
import com.unshoo.pixelmusic.presentation.components.explore.ExploreSectionHeader
import com.unshoo.pixelmusic.presentation.components.explore.ExploreSkeleton
import com.unshoo.pixelmusic.presentation.components.explore.ExpressiveButton
import com.unshoo.pixelmusic.presentation.components.explore.ExpressiveButtonGroup
import com.unshoo.pixelmusic.presentation.components.explore.ExpressiveCard
import com.unshoo.pixelmusic.presentation.model.ExploreAlbumItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreArtistItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreChartsUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreChipUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreHeroItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreScreenUiState
import com.unshoo.pixelmusic.presentation.model.ExploreSongItemUiModel
import com.unshoo.pixelmusic.presentation.screens.expressive.ScallopFlowerShape
import com.unshoo.pixelmusic.ui.theme.MaterialExpressiveTheme
import com.unshoo.pixelmusic.ui.theme.Shapes

// ============================================================================
//  Explore Mock Data Factory (Standalone & Type-Safe)
// ============================================================================

internal object ExploreMockData {
    val sampleChips = listOf(
        ExploreChipUiModel(id = "all", title = "All", isSelected = true),
        ExploreChipUiModel(id = "local", title = "Local", isSelected = false),
        ExploreChipUiModel(id = "relax", title = "Relax", isSelected = false),
        ExploreChipUiModel(id = "workout", title = "Workout", isSelected = false),
        ExploreChipUiModel(id = "focus", title = "Focus", isSelected = false),
        ExploreChipUiModel(id = "party", title = "Party", isSelected = false),
        ExploreChipUiModel(id = "romance", title = "Romance", isSelected = false),
        ExploreChipUiModel(id = "energize", title = "Energize", isSelected = false)
    )

    val sampleGenres = listOf(
        Genre(id = "pop", name = "Pop"),
        Genre(id = "rock", name = "Rock"),
        Genre(id = "hiphop", name = "Hip-Hop"),
        Genre(id = "electronic", name = "Electronic"),
        Genre(id = "rnb", name = "R&B"),
        Genre(id = "indie", name = "Indie"),
        Genre(id = "classical", name = "Classical"),
        Genre(id = "jazz", name = "Jazz")
    )

    val sampleHeroItems = listOf(
        ExploreHeroItemUiModel(
            id = "hero_1",
            title = "Midnight City",
            subtitle = "M83 • Hurry Up, We're Dreaming",
            artworkUrl = null,
            badgeText = "Spotlight"
        ),
        ExploreHeroItemUiModel(
            id = "hero_2",
            title = "Starboy",
            subtitle = "The Weeknd ft. Daft Punk",
            artworkUrl = null,
            badgeText = "Top Chart"
        ),
        ExploreHeroItemUiModel(
            id = "hero_3",
            title = "Stay With Me",
            subtitle = "Calvin Harris, Justin Timberlake, Halsey",
            artworkUrl = null,
            badgeText = "Featured"
        )
    )

    val sampleQuickPicks = listOf(
        ExploreSongItemUiModel(
            id = "qp_1",
            title = "Blinding Lights",
            subtitle = "The Weeknd",
            artworkUrl = null,
            durationText = "3:20",
            nativeSong = Song.emptySong().copy(id = "qp_1", title = "Blinding Lights", artist = "The Weeknd", album = "After Hours", duration = 200_000L)
        ),
        ExploreSongItemUiModel(
            id = "qp_2",
            title = "As It Was",
            subtitle = "Harry Styles",
            artworkUrl = null,
            durationText = "2:47",
            nativeSong = Song.emptySong().copy(id = "qp_2", title = "As It Was", artist = "Harry Styles", album = "Harry's House", duration = 167_000L)
        ),
        ExploreSongItemUiModel(
            id = "qp_3",
            title = "Heat Waves",
            subtitle = "Glass Animals",
            artworkUrl = null,
            durationText = "3:58",
            nativeSong = Song.emptySong().copy(id = "qp_3", title = "Heat Waves", artist = "Glass Animals", album = "Dreamland", duration = 238_000L)
        ),
        ExploreSongItemUiModel(
            id = "qp_4",
            title = "Levitating",
            subtitle = "Dua Lipa",
            artworkUrl = null,
            durationText = "3:23",
            nativeSong = Song.emptySong().copy(id = "qp_4", title = "Levitating", artist = "Dua Lipa", album = "Future Nostalgia", duration = 203_000L)
        ),
        ExploreSongItemUiModel(
            id = "qp_5",
            title = "Save Your Tears",
            subtitle = "The Weeknd",
            artworkUrl = null,
            durationText = "3:35",
            nativeSong = Song.emptySong().copy(id = "qp_5", title = "Save Your Tears", artist = "The Weeknd", album = "After Hours", duration = 215_000L)
        ),
        ExploreSongItemUiModel(
            id = "qp_6",
            title = "Stay",
            subtitle = "The Kid LAROI & Justin Bieber",
            artworkUrl = null,
            durationText = "2:21",
            nativeSong = Song.emptySong().copy(id = "qp_6", title = "Stay", artist = "The Kid LAROI & Justin Bieber", album = "F*CK LOVE 3", duration = 141_000L)
        )
    )

    val sampleNewReleases = listOf(
        ExploreAlbumItemUiModel(
            id = "rel_1",
            title = "Dawn FM",
            subtitle = "The Weeknd",
            year = "2024",
            browseId = "rel_1"
        ),
        ExploreAlbumItemUiModel(
            id = "rel_2",
            title = "Fine Line",
            subtitle = "Harry Styles",
            year = "2024",
            browseId = "rel_2"
        ),
        ExploreAlbumItemUiModel(
            id = "rel_3",
            title = "Future Nostalgia",
            subtitle = "Dua Lipa",
            year = "2024",
            browseId = "rel_3"
        ),
        ExploreAlbumItemUiModel(
            id = "rel_4",
            title = "Midnights",
            subtitle = "Taylor Swift",
            year = "2024",
            browseId = "rel_4"
        )
    )

    val sampleAlbumsForYou = listOf(
        ExploreAlbumItemUiModel(
            id = "alb_1",
            title = "After Hours",
            subtitle = "The Weeknd",
            year = "2020",
            browseId = "alb_1"
        ),
        ExploreAlbumItemUiModel(
            id = "alb_2",
            title = "Dreamland",
            subtitle = "Glass Animals",
            year = "2020",
            browseId = "alb_2"
        ),
        ExploreAlbumItemUiModel(
            id = "alb_3",
            title = "Harry's House",
            subtitle = "Harry Styles",
            year = "2022",
            browseId = "alb_3"
        ),
        ExploreAlbumItemUiModel(
            id = "alb_4",
            title = "Currents",
            subtitle = "Tame Impala",
            year = "2015",
            browseId = "alb_4"
        )
    )

    val sampleArtists = listOf(
        ExploreArtistItemUiModel(id = "art_1", name = "The Weeknd", browseId = "art_1"),
        ExploreArtistItemUiModel(id = "art_2", name = "Dua Lipa", browseId = "art_2"),
        ExploreArtistItemUiModel(id = "art_3", name = "Harry Styles", browseId = "art_3"),
        ExploreArtistItemUiModel(id = "art_4", name = "Taylor Swift", browseId = "art_4"),
        ExploreArtistItemUiModel(id = "art_5", name = "Glass Animals", browseId = "art_5"),
        ExploreArtistItemUiModel(id = "art_6", name = "Billie Eilish", browseId = "art_6")
    )

    val sampleCharts = ExploreChartsUiModel(
        tracks = sampleQuickPicks.take(5),
        collections = sampleNewReleases.take(3)
    )

    val sampleContentState = ExploreScreenUiState.Content(
        isRefreshing = false,
        isContinuationLoading = false,
        selectedCategory = "All",
        categories = sampleChips,
        heroItems = sampleHeroItems,
        quickPicks = sampleQuickPicks,
        newReleases = sampleNewReleases,
        albumsForYou = sampleAlbumsForYou,
        charts = sampleCharts,
        artistsForYou = sampleArtists,
        dynamicShelves = emptyList(),
        hasMoreContent = true
    )
}

// ============================================================================
//  Stateless Preview Helper Composables
// ============================================================================

@Composable
internal fun ExploreScreenPreviewHarness(
    state: ExploreScreenUiState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (state) {
            is ExploreScreenUiState.Loading -> {
                ExploreSkeleton()
            }
            is ExploreScreenUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveCard(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Unable to load content",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            ExpressiveButton(onClick = {}) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Retry", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            is ExploreScreenUiState.Content -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    item(key = "chips", contentType = "chips") {
                        ExploreChipsRow(chips = state.categories, onChipClick = {})
                    }
                    item(key = "hero", contentType = "hero") {
                        ExploreHeroCarouselUiModels(
                            items = state.heroItems,
                            currentSongId = null,
                            onItemClick = {}
                        )
                    }
                    item(key = "quick_picks", contentType = "quick_picks") {
                        Column {
                            ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
                            Spacer(Modifier.height(12.dp))
                            ExploreQuickPicksUiModels(
                                items = state.quickPicks,
                                currentSongId = "qp_1",
                                onItemClick = {},
                                onItemLongClick = {}
                            )
                        }
                    }
                    item(key = "new_releases", contentType = "new_releases") {
                        Column {
                            ExploreSectionHeader(title = "New Releases", actionLabel = "See all", onAction = {})
                            Spacer(Modifier.height(12.dp))
                            ExploreAlbumsRow(albums = state.newReleases, onAlbumClick = {})
                        }
                    }
                    state.charts?.let { charts ->
                        item(key = "charts", contentType = "charts") {
                            Column {
                                ExploreSectionHeader(title = "Charts", actionLabel = "See all", onAction = {})
                                Spacer(Modifier.height(12.dp))
                                ExploreChartsPreviewContent(charts = charts)
                            }
                        }
                    }
                    item(key = "albums", contentType = "albums") {
                        Column {
                            ExploreSectionHeader(title = "Albums For You", subtitle = "Based on your recent listening")
                            Spacer(Modifier.height(12.dp))
                            ExploreAlbumsRow(albums = state.albumsForYou, onAlbumClick = {})
                        }
                    }
                    item(key = "artists", contentType = "artists") {
                        Column {
                            ExploreSectionHeader(title = "Artists For You", subtitle = "Musicians in your rotation")
                            Spacer(Modifier.height(12.dp))
                            ExploreArtistsPreviewContent(artists = state.artistsForYou)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExploreArtistsPreviewContent(
    artists: List<ExploreArtistItemUiModel>,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(artists, key = { _, it -> "artist_${it.id}" }) { index, artist ->
            val isTop = index < 3
            val avatarShape = remember(isTop) {
                if (isTop) ScallopFlowerShape(petals = 12, depthRatio = 0.09f)
                else ScallopFlowerShape(petals = 10, depthRatio = 0.08f)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(96.dp)
            ) {
                Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = avatarShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (isTop) androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        ) else androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.size(84.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = artist.name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun ExploreChartsPreviewContent(
    charts: ExploreChartsUiModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        charts.tracks.take(5).forEachIndexed { index, track ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (index == 0) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.subtitle ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (index == 0) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
//  1. Full ExploreScreen Previews (Loading, Content, Error in Light & Dark)
// ============================================================================

@Preview(name = "Explore Screen - Loading (Light)", showBackground = true)
@Composable
fun ExploreScreenLoadingPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        ExploreScreenPreviewHarness(state = ExploreScreenUiState.Loading)
    }
}

@Preview(name = "Explore Screen - Loading (Dark)", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreScreenLoadingPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        ExploreScreenPreviewHarness(state = ExploreScreenUiState.Loading)
    }
}

@Preview(name = "Explore Screen - Content (Light)", showBackground = true)
@Composable
fun ExploreScreenContentPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        ExploreScreenPreviewHarness(state = ExploreMockData.sampleContentState)
    }
}

@Preview(name = "Explore Screen - Content (Dark)", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreScreenContentPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        ExploreScreenPreviewHarness(state = ExploreMockData.sampleContentState)
    }
}

@Preview(name = "Explore Screen - Error (Light)", showBackground = true)
@Composable
fun ExploreScreenErrorPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        ExploreScreenPreviewHarness(
            state = ExploreScreenUiState.Error("Network connection timed out. Please check your internet connection.")
        )
    }
}

@Preview(name = "Explore Screen - Error (Dark)", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreScreenErrorPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        ExploreScreenPreviewHarness(
            state = ExploreScreenUiState.Error("Network connection timed out. Please check your internet connection.")
        )
    }
}

// ============================================================================
//  2. Individual Section Previews (Light & Dark)
// ============================================================================

@Preview(name = "Mood & Genres Section - Light", showBackground = true)
@Composable
fun MoodAndGenresSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExploreSectionHeader(title = "Moods & Categories")
                ExploreChipsRow(chips = ExploreMockData.sampleChips, onChipClick = {})
                ExploreSectionHeader(title = "Featured Genres")
                ExploreGenreChips(genres = ExploreMockData.sampleGenres, onGenreClick = {})
            }
        }
    }
}

@Preview(name = "Mood & Genres Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun MoodAndGenresSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExploreSectionHeader(title = "Moods & Categories")
                ExploreChipsRow(chips = ExploreMockData.sampleChips, onChipClick = {})
                ExploreSectionHeader(title = "Featured Genres")
                ExploreGenreChips(genres = ExploreMockData.sampleGenres, onGenreClick = {})
            }
        }
    }
}

@Preview(name = "Quick Picks Section - Light", showBackground = true)
@Composable
fun QuickPicksSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreQuickPicksUiModels(
                items = ExploreMockData.sampleQuickPicks,
                currentSongId = "qp_1",
                onItemClick = {},
                onItemLongClick = {}
            )
        }
    }
}

@Preview(name = "Quick Picks Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun QuickPicksSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreQuickPicksUiModels(
                items = ExploreMockData.sampleQuickPicks,
                currentSongId = "qp_2",
                onItemClick = {},
                onItemLongClick = {}
            )
        }
    }
}

@Preview(name = "New Release Albums Section - Light", showBackground = true)
@Composable
fun NewReleaseAlbumsSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "New Releases", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreAlbumsRow(
                albums = ExploreMockData.sampleNewReleases,
                onAlbumClick = {}
            )
        }
    }
}

@Preview(name = "New Release Albums Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun NewReleaseAlbumsSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "New Releases", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreAlbumsRow(
                albums = ExploreMockData.sampleNewReleases,
                onAlbumClick = {}
            )
        }
    }
}

@Preview(name = "Albums For You Section - Light", showBackground = true)
@Composable
fun AlbumsForYouSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Albums For You", subtitle = "Based on your recent listening")
            Spacer(Modifier.height(12.dp))
            ExploreAlbumsRow(
                albums = ExploreMockData.sampleAlbumsForYou,
                onAlbumClick = {}
            )
        }
    }
}

@Preview(name = "Albums For You Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun AlbumsForYouSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Albums For You", subtitle = "Based on your recent listening")
            Spacer(Modifier.height(12.dp))
            ExploreAlbumsRow(
                albums = ExploreMockData.sampleAlbumsForYou,
                onAlbumClick = {}
            )
        }
    }
}

@Preview(name = "Charts Section - Light", showBackground = true)
@Composable
fun ChartsSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Charts", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreChartsPreviewContent(charts = ExploreMockData.sampleCharts)
        }
    }
}

@Preview(name = "Charts Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ChartsSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Charts", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreChartsPreviewContent(charts = ExploreMockData.sampleCharts)
        }
    }
}

@Preview(name = "Artists For You Section - Light", showBackground = true)
@Composable
fun ArtistsForYouSectionPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Artists For You", subtitle = "Musicians in your rotation")
            Spacer(Modifier.height(12.dp))
            ExploreArtistsPreviewContent(artists = ExploreMockData.sampleArtists)
        }
    }
}

@Preview(name = "Artists For You Section - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ArtistsForYouSectionPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Artists For You", subtitle = "Musicians in your rotation")
            Spacer(Modifier.height(12.dp))
            ExploreArtistsPreviewContent(artists = ExploreMockData.sampleArtists)
        }
    }
}

// ============================================================================
//  3. Key M3 Expressive Component Variants & Hero Carousel Previews
// ============================================================================

@Preview(name = "Hero Carousel - Light", showBackground = true)
@Composable
fun ExploreHeroCarouselPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Featured For You")
            Spacer(Modifier.height(12.dp))
            ExploreHeroCarouselUiModels(
                items = ExploreMockData.sampleHeroItems,
                currentSongId = null,
                onItemClick = {}
            )
        }
    }
}

@Preview(name = "Hero Carousel - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreHeroCarouselPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Featured For You")
            Spacer(Modifier.height(12.dp))
            ExploreHeroCarouselUiModels(
                items = ExploreMockData.sampleHeroItems,
                currentSongId = "hero_1",
                onItemClick = {}
            )
        }
    }
}

@Preview(name = "Expressive Components - Light", showBackground = true)
@Composable
fun ExpressiveComponentsPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Material 3 Expressive Components",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                ExploreLinearWavyProgressIndicator(
                    progress = { 0.65f },
                    modifier = Modifier.fillMaxWidth()
                )
                ExploreLinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                ExploreCircularWavyProgressIndicator()
                ExpressiveButton(onClick = {}) {
                    Text("Shape Morphing Button")
                }
                var selectedPill by remember { mutableIntStateOf(0) }
                ExpressiveButtonGroup(
                    items = listOf("Songs", "Albums", "Artists"),
                    selectedIndex = selectedPill,
                    onSelect = { selectedPill = it }
                )
                ExpressiveCard(onClick = {}, shape = Shapes.large) {
                    Text(
                        text = "Expressive Card Container (Shapes.large)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Expressive Components - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExpressiveComponentsPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Material 3 Expressive Components",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                ExploreLinearWavyProgressIndicator(
                    progress = { 0.65f },
                    modifier = Modifier.fillMaxWidth()
                )
                ExploreLinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                ExploreCircularWavyProgressIndicator()
                ExpressiveButton(onClick = {}) {
                    Text("Shape Morphing Button")
                }
                var selectedPill by remember { mutableIntStateOf(1) }
                ExpressiveButtonGroup(
                    items = listOf("Songs", "Albums", "Artists"),
                    selectedIndex = selectedPill,
                    onSelect = { selectedPill = it }
                )
                ExpressiveCard(onClick = {}, shape = Shapes.large) {
                    Text(
                        text = "Expressive Card Container (Shapes.large)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Skeleton Loading - Light", showBackground = true)
@Composable
fun ExploreSkeletonPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false, dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ExploreSkeleton()
        }
    }
}

@Preview(name = "Skeleton Loading - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreSkeletonPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true, dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ExploreSkeleton()
        }
    }
}