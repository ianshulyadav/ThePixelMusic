package com.unshoo.pixelmusic.presentation.screens.explore

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.presentation.components.explore.ExploreAlbumsRow
import com.unshoo.pixelmusic.presentation.components.explore.ExploreChipsRow
import com.unshoo.pixelmusic.presentation.components.explore.ExploreCircularWavyProgressIndicator
import com.unshoo.pixelmusic.presentation.components.explore.ExploreEmptyHint
import com.unshoo.pixelmusic.presentation.components.explore.ExploreHeroCarouselUiModels
import com.unshoo.pixelmusic.presentation.components.explore.ExploreLinearWavyProgressIndicator
import com.unshoo.pixelmusic.presentation.components.explore.ExploreQuickPicksUiModels
import com.unshoo.pixelmusic.presentation.components.explore.ExploreSectionHeader
import com.unshoo.pixelmusic.presentation.components.explore.ExploreSkeleton
import com.unshoo.pixelmusic.presentation.components.explore.ExpressiveButton
import com.unshoo.pixelmusic.presentation.components.explore.ExpressiveButtonGroup
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.presentation.model.ExploreAlbumItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreChipUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreHeroItemUiModel
import com.unshoo.pixelmusic.presentation.model.ExploreSongItemUiModel
import com.unshoo.pixelmusic.ui.theme.MaterialExpressiveTheme

// ============================================================================
//  Preview Mock Datasets
// ============================================================================

private val SampleChips = listOf(
    ExploreChipUiModel(id = "all", title = "All", isSelected = true),
    ExploreChipUiModel(id = "local", title = "Local", isSelected = false),
    ExploreChipUiModel(id = "relax", title = "Relax", isSelected = false),
    ExploreChipUiModel(id = "workout", title = "Workout", isSelected = false),
    ExploreChipUiModel(id = "focus", title = "Focus", isSelected = false),
    ExploreChipUiModel(id = "party", title = "Party", isSelected = false)
)

private val SampleHeroItems = listOf(
    ExploreHeroItemUiModel(
        id = "hero_1",
        title = "Midnight City",
        subtitle = "M83",
        artworkUrl = null,
        badgeText = "Spotlight"
    ),
    ExploreHeroItemUiModel(
        id = "hero_2",
        title = "Starboy",
        subtitle = "The Weeknd",
        artworkUrl = null,
        badgeText = "Top Chart"
    )
)

private val SampleQuickPicks = listOf(
    ExploreSongItemUiModel(
        id = "qp_1",
        title = "Blinding Lights",
        subtitle = "The Weeknd",
        artworkUrl = null,
        nativeSong = Song.emptySong().copy(id = "qp_1", title = "Blinding Lights", artist = "The Weeknd", album = "", duration = 200_000L)
    ),
    ExploreSongItemUiModel(
        id = "qp_2",
        title = "As It Was",
        subtitle = "Harry Styles",
        artworkUrl = null,
        nativeSong = Song.emptySong().copy(id = "qp_2", title = "As It Was", artist = "Harry Styles", album = "", duration = 167_000L)
    ),
    ExploreSongItemUiModel(
        id = "qp_3",
        title = "Heat Waves",
        subtitle = "Glass Animals",
        artworkUrl = null,
        nativeSong = Song.emptySong().copy(id = "qp_3", title = "Heat Waves", artist = "Glass Animals", album = "", duration = 238_000L)
    ),
    ExploreSongItemUiModel(
        id = "qp_4",
        title = "Levitating",
        subtitle = "Dua Lipa",
        artworkUrl = null,
        nativeSong = Song.emptySong().copy(id = "qp_4", title = "Levitating", artist = "Dua Lipa", album = "", duration = 203_000L)
    )
)

private val SampleAlbums = listOf(
    ExploreAlbumItemUiModel(
        id = "alb_1",
        title = "Dawn FM",
        subtitle = "The Weeknd",
        year = "2022"
    ),
    ExploreAlbumItemUiModel(
        id = "alb_2",
        title = "Fine Line",
        subtitle = "Harry Styles",
        year = "2019"
    ),
    ExploreAlbumItemUiModel(
        id = "alb_3",
        title = "Future Nostalgia",
        subtitle = "Dua Lipa",
        year = "2020"
    )
)

// ============================================================================
//  Jetpack Compose Previews (Light and Dark Themes)
// ============================================================================

@Preview(name = "Expressive Components - Light", showBackground = true)
@Composable
fun ExpressiveComponentsPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false) {
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
            }
        }
    }
}

@Preview(name = "Expressive Components - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExpressiveComponentsPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true) {
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
            }
        }
    }
}

@Preview(name = "Hero Carousel - Light", showBackground = true)
@Composable
fun ExploreHeroCarouselPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Featured For You")
            Spacer(Modifier.height(12.dp))
            ExploreHeroCarouselUiModels(
                items = SampleHeroItems,
                currentSongId = null,
                onItemClick = {}
            )
        }
    }
}

@Preview(name = "Hero Carousel - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreHeroCarouselPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Featured For You")
            Spacer(Modifier.height(12.dp))
            ExploreHeroCarouselUiModels(
                items = SampleHeroItems,
                currentSongId = "hero_1",
                onItemClick = {}
            )
        }
    }
}

@Preview(name = "Quick Picks - Light", showBackground = true)
@Composable
fun ExploreQuickPicksPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreQuickPicksUiModels(
                items = SampleQuickPicks,
                currentSongId = "qp_1",
                onItemClick = {},
                onItemLongClick = {}
            )
        }
    }
}

@Preview(name = "Quick Picks - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreQuickPicksPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
            Spacer(Modifier.height(12.dp))
            ExploreQuickPicksUiModels(
                items = SampleQuickPicks,
                currentSongId = null,
                onItemClick = {},
                onItemLongClick = {}
            )
        }
    }
}

@Preview(name = "Albums Row - Light", showBackground = true)
@Composable
fun ExploreAlbumsRowPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp)
        ) {
            ExploreSectionHeader(title = "Albums For You")
            Spacer(Modifier.height(12.dp))
            ExploreAlbumsRow(
                albums = SampleAlbums,
                onAlbumClick = {}
            )
        }
    }
}

@Preview(name = "Skeleton Loading - Light", showBackground = true)
@Composable
fun ExploreSkeletonPreviewLight() {
    MaterialExpressiveTheme(darkTheme = false) {
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
    MaterialExpressiveTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ExploreSkeleton()
        }
    }
}

@Preview(name = "Explore Screen Content - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun ExploreScreenContentPreviewDark() {
    MaterialExpressiveTheme(darkTheme = true) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item(key = "chips", contentType = "chips") {
                ExploreChipsRow(chips = SampleChips, onChipClick = {})
            }
            item(key = "hero", contentType = "hero") {
                ExploreHeroCarouselUiModels(
                    items = SampleHeroItems,
                    currentSongId = null,
                    onItemClick = {}
                )
            }
            item(key = "quick_picks", contentType = "quick_picks") {
                Column {
                    ExploreSectionHeader(title = "Quick Picks", actionLabel = "See all", onAction = {})
                    Spacer(Modifier.height(12.dp))
                    ExploreQuickPicksUiModels(
                        items = SampleQuickPicks,
                        currentSongId = null,
                        onItemClick = {},
                        onItemLongClick = {}
                    )
                }
            }
            item(key = "albums", contentType = "albums") {
                Column {
                    ExploreSectionHeader(title = "Albums For You")
                    Spacer(Modifier.height(12.dp))
                    ExploreAlbumsRow(albums = SampleAlbums, onAlbumClick = {})
                }
            }
        }
    }
}
