package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class UrlEndpoint(
    val url: String? = null,
    val target: String? = null,
)
