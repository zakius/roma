package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class SubscriptionButton(
    val subscribeButtonRenderer: SubscribeButtonRenderer,
) {
    @Serializable
    class SubscribeButtonRenderer(
        val subscribed: Boolean,
        val channelId: String,
        val longSubscriberCountText: Runs? = null,
        val shortSubscriberCountText: Runs? = null,
    )
}
