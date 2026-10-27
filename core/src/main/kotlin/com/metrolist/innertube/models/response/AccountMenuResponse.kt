package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.Thumbnail
import kotlinx.serialization.Serializable

@Serializable
class AccountMenuResponse(
    val actions: List<Action>,
) {
    @Serializable
    class Action(
        val openPopupAction: OpenPopupAction,
    ) {
        @Serializable
        class OpenPopupAction(
            val popup: Popup,
        ) {
            @Serializable
            class Popup(
                val multiPageMenuRenderer: MultiPageMenuRenderer,
            ) {
                @Serializable
                class MultiPageMenuRenderer(
                    val header: Header?,
                ) {
                    @Serializable
                    class Header(
                        val activeAccountHeaderRenderer: ActiveAccountHeaderRenderer,
                    ) {
                        @Serializable
                        class ActiveAccountHeaderRenderer(
                            val accountName: Runs,
                            val email: Runs?,
                            val channelHandle: Runs?,
                            val accountPhoto: Thumbnails,
                        ) {
                            fun toAccountInfo() =
                                AccountInfo(
                                    name = accountName.runs!!.first().text,
                                    email = email?.runs?.first()?.text,
                                    channelHandle = channelHandle?.runs?.first()?.text,
                                    thumbnailUrl = accountPhoto.thumbnails.lastOrNull()?.url,
                                )
                        }
                    }
                }
            }
        }
    }
}
