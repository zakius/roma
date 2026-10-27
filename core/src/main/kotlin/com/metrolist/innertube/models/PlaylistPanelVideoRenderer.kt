package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class PlaylistPanelVideoRenderer(
    val title: Runs?,
    val lengthText: Runs?,
    val longBylineText: Runs?,
    val badges: List<Badges>?,
    val videoId: String?,
    val selected: Boolean,
    val thumbnail: Thumbnails,
    val menu: Menu?,
    val navigationEndpoint: NavigationEndpoint,
)
