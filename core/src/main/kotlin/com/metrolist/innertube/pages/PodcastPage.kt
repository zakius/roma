package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.splitBySeparator

class PodcastPage(
    val podcast: PodcastItem,
    val episodes: List<EpisodeItem>,
    val continuation: String?,
    val isChannelSubscribed: Boolean = false,
) {
    companion object {
        fun extractPodcastByline(runs: List<Run>?): List<Artist> {
            val linked = PageHelper.extractArtists(runs)
            if (linked.isNotEmpty()) return linked

            val podcast =
                runs?.firstOrNull {
                    it.text.isNotBlank() && it.navigationEndpoint?.browseEndpoint?.isPodcastEndpoint == true
                }
            if (podcast != null) return listOf(Artist(podcast.text.trim(), null))

            val channel = runs?.splitBySeparator()?.firstOrNull()?.firstOrNull()
            return channel
                ?.takeIf { it.text.isNotBlank() && it.navigationEndpoint == null }
                ?.let { listOf(Artist(it.text.trim(), null)) }
                .orEmpty()
        }
    }
}
