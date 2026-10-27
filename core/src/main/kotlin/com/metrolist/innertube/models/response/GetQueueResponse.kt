package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.PlaylistPanelRenderer
import kotlinx.serialization.Serializable

@Serializable
class GetQueueResponse(
    val queueDatas: List<QueueData>,
) {
    @Serializable
    class QueueData(
        val content: PlaylistPanelRenderer.Content,
    )
}
