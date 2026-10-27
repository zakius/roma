package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.PlaylistPanelRenderer
import com.metrolist.innertube.models.Tabs
import com.metrolist.innertube.models.YouTubeDataPage
import kotlinx.serialization.Serializable

@Serializable
class NextResponse(
    val contents: Contents,
    val continuationContents: ContinuationContents?,
    val currentVideoEndpoint: NavigationEndpoint?,
) {
    @Serializable
    class Contents(
        val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer?,
        val twoColumnWatchNextResults: YouTubeDataPage.Contents.TwoColumnWatchNextResults?,
    ) {
        @Serializable
        class SingleColumnMusicWatchNextResultsRenderer(
            val tabbedRenderer: TabbedRenderer?,
        ) {
            @Serializable
            class TabbedRenderer(
                val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer?,
            ) {
                @Serializable
                class WatchNextTabbedResultsRenderer(
                    val tabs: List<Tabs.Tab>,
                )
            }
        }
    }

    @Serializable
    class ContinuationContents(
        val playlistPanelContinuation: PlaylistPanelRenderer,
    )
}
