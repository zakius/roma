package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicCarouselShelfRenderer(
    val header: Header?,
    val contents: List<Content>,
) {
    @Serializable
    class Header(
        val musicCarouselShelfBasicHeaderRenderer: MusicCarouselShelfBasicHeaderRenderer,
    ) {
        @Serializable
        class MusicCarouselShelfBasicHeaderRenderer(
            val strapline: Runs?,
            val title: Runs,
            val thumbnail: ThumbnailRenderer?,
            val moreContentButton: Button?,
        )
    }

    @Serializable
    class Content(
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer?,
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
        val musicMultiRowListItemRenderer: MusicMultiRowListItemRenderer?,
    )
}
