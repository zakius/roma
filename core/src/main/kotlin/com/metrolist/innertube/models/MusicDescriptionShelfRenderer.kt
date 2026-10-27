package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicDescriptionShelfRenderer(
    val header: Runs?,
    val subheader: Runs?,
    val description: Runs?,
    val footer: Runs?,
)
