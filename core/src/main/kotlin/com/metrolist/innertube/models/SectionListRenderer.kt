package com.metrolist.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class SectionListRenderer(
    val header: Header?,
    val contents: List<Content>?,
    val continuations: List<Continuation>?,
) {
    @Serializable
    class Header(
        val chipCloudRenderer: ChipCloudRenderer?,
    ) {
        @Serializable
        class ChipCloudRenderer(
            val chips: List<Chip>,
        ) {
            @Serializable
            class Chip(
                val chipCloudChipRenderer: ChipCloudChipRenderer,
            ) {
                @Serializable
                class ChipCloudChipRenderer(
                    val isSelected: Boolean = false,
                    val navigationEndpoint: NavigationEndpoint? = null,
                    val onDeselectedCommand: NavigationEndpoint? = null,
                    // The close button doesn't have the following two fields
                    val text: Runs?,
                    val uniqueId: String?,
                )
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    class Content(
        @JsonNames("musicImmersiveCarouselShelfRenderer")
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer?,
        val musicShelfRenderer: MusicShelfRenderer?,
        val musicCardShelfRenderer: MusicCardShelfRenderer?,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer?,
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer?,
        val musicEditablePlaylistDetailHeaderRenderer: MusicEditablePlaylistDetailHeaderRenderer?,
        val gridRenderer: GridRenderer?,
        val itemSectionRenderer: ItemSectionRenderer?,
    )
}

@Serializable
class ItemSectionRenderer(
    val contents: List<Content>?,
    val header: Header? = null,
) {
    @Serializable
    class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
        val musicShelfRenderer: MusicShelfRenderer? = null,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = null,
        val gridRenderer: GridRenderer? = null,
        // roma: search spelling corrections.
        val showingResultsForRenderer: Correction? = null,
        val didYouMeanRenderer: Correction? = null,
    )

    @Serializable
    class Correction(
        val correctedQuery: Runs? = null,
        val originalQueryEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    class Header(
        val itemSectionTabbedHeaderRenderer: ItemSectionTabbedHeaderRenderer?,
    )

    @Serializable
    class ItemSectionTabbedHeaderRenderer(
        val title: Runs?,
    )
}
