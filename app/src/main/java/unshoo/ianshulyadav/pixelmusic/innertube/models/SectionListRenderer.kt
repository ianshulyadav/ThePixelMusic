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

@Serializable
data class SectionListRenderer(
    val header: Header? = null,
    val contents: List<Content>? = null,
    val continuations: List<Continuation>? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Header(
        @JsonNames("feedFilterChipBarRenderer", "musicHeaderRenderer")
        val chipCloudRenderer: ChipCloudRenderer? = null,
    ) {
        @Serializable
        data class ChipCloudRenderer(
            val chips: List<Chip> = emptyList(),
        ) {
            @Serializable
            data class Chip(
                val chipCloudChipRenderer: ChipCloudChipRenderer = ChipCloudChipRenderer(),
            ) {
                @Serializable
                data class ChipCloudChipRenderer(
                    val isSelected: Boolean = false,
                    val navigationEndpoint: NavigationEndpoint = NavigationEndpoint(),
                    val onDeselectedCommand: NavigationEndpoint? = null,
                    // The close button doesn't have the following two fields
                    val text: Runs? = null,
                    val uniqueId: String? = null,
                )
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Content(
        @JsonNames("musicImmersiveCarouselShelfRenderer")
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer? = null,
        val musicShelfRenderer: MusicShelfRenderer? = null,
        val musicCardShelfRenderer: MusicCardShelfRenderer? = null,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = null,
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer? = null,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer? = null,
        val musicEditablePlaylistDetailHeaderRenderer: MusicEditablePlaylistDetailHeaderRenderer? = null,
        val gridRenderer: GridRenderer? = null,
        val itemSectionRenderer: ItemSectionRenderer? = null,
        val didYouMeanRenderer: DidYouMeanRenderer? = null,
        val showingResultsForRenderer: ShowingResultsForRenderer? = null,
    )

    @Serializable
    data class DidYouMeanRenderer(
        val correctedQuery: Runs? = null,
        val didYouMeanEndpoint: NavigationEndpoint? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    data class ShowingResultsForRenderer(
        val correctedQuery: Runs? = null,
        val originalQuery: Runs? = null,
        val correctedQueryEndpoint: NavigationEndpoint? = null,
        val originalQueryEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<ItemSectionContent>? = null,
    ) {
        @Serializable
        data class ItemSectionContent(
            val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
            val musicCardShelfRenderer: MusicCardShelfRenderer? = null,
            val gridRenderer: GridRenderer? = null,
            val musicShelfRenderer: MusicShelfRenderer? = null,
            val didYouMeanRenderer: DidYouMeanRenderer? = null,
            val showingResultsForRenderer: ShowingResultsForRenderer? = null,
        )
    }
}
