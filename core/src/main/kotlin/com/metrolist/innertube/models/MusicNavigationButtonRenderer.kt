package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicNavigationButtonRenderer(
    val buttonText: Runs,
    val solid: Solid?,
    val iconStyle: IconStyle?,
    val clickCommand: NavigationEndpoint,
) {
    @Serializable
    class Solid(
        val leftStripeColor: Long,
    )

    @Serializable
    class IconStyle(
        val icon: Icon,
    )
}
