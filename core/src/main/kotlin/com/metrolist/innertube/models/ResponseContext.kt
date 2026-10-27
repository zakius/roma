package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class ResponseContext(
    val visitorData: String?,
    val serviceTrackingParams: List<ServiceTrackingParam>?,
) {
    @Serializable
    class ServiceTrackingParam(
        val params: List<Param>,
        val service: String,
    ) {
        @Serializable
        class Param(
            val key: String,
            val value: String,
        )
    }
}
