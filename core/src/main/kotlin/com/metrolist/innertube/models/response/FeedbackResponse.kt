package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
class FeedbackResponse(
    val feedbackResponses: List<Status>,
) {
    @Serializable
    class Status(
        val isProcessed: Boolean,
    )
}
