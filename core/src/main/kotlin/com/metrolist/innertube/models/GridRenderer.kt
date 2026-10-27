package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class GridRenderer(
    val header: Header?,
    val items: List<Item>,
    val continuations: List<Continuation>?,
) {
    @Serializable
    class Header(
        val gridHeaderRenderer: GridHeaderRenderer,
    ) {
        @Serializable
        class GridHeaderRenderer(
            val title: Runs,
        )
    }

    @Serializable
    class Item(
        val musicNavigationButtonRenderer: MusicNavigationButtonRenderer?,
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer?,
    )
}
