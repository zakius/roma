package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Badges(
    val musicInlineBadgeRenderer: MusicInlineBadgeRenderer?,
) {
    @Serializable
    class MusicInlineBadgeRenderer(
        val icon: Icon,
    )
}
