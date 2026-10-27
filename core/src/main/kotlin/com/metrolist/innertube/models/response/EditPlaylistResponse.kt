package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
class EditPlaylistResponse(
    val newHeader: BrowseResponse.Header?,
)
