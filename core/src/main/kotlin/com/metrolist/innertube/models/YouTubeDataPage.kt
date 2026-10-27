package com.metrolist.innertube.models


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class YouTubeDataPage(
    @SerialName("contents")
    val contents: Contents? = null,
) {
    @Serializable
    class Contents(
        @SerialName("twoColumnWatchNextResults")
        val twoColumnWatchNextResults: TwoColumnWatchNextResults? = null,
    ) {
        @Serializable
        class TwoColumnWatchNextResults(
            @SerialName("results")
            val results: Results? = null,
        ) {
            @Serializable
            class Results(
                @SerialName("results")
                val results: Results? = null,
            ) {
                @Serializable
                class Results(
                    @SerialName("contents")
                    val content: List<Content?>? = null,
                ) {
                    @Serializable
                    class Content(
                        @SerialName("videoPrimaryInfoRenderer")
                        val videoPrimaryInfoRenderer: VideoPrimaryInfoRenderer? = null,
                        @SerialName("videoSecondaryInfoRenderer")
                        val videoSecondaryInfoRenderer: VideoSecondaryInfoRenderer? = null,
                        @SerialName("itemSectionRenderer")
                        val itemSectionRenderer: ItemSectionRenderer? = null,
                    ) {
                        @Serializable
                        class ItemSectionRenderer(
                            @SerialName("contents")
                            val contents: List<Content?>? = null,
                        ) {
                            @Serializable
                            class Content(
                                @SerialName("continuationItemRenderer")
                                val continuationItemRenderer: ContinuationItemRenderer? = null,
                            ) {
                                @Serializable
                                class ContinuationItemRenderer(
                                    @SerialName("trigger")
                                    val trigger: String? = null,
                                    @SerialName("continuationEndpoint")
                                    val continuationEndpoint: ContinuationEndpoint? = null,
                                ) {
                                    @Serializable
                                    class ContinuationEndpoint(
                                        @SerialName("clickTrackingParams")
                                        val clickTrackingParams: String? = null,
                                        @SerialName("continuationCommand")
                                        val continuationCommand: ContinuationCommand? = null,
                                    ) {
                                        @Serializable
                                        class ContinuationCommand(
                                            @SerialName("token")
                                            val token: String? = null,
                                            @SerialName("request")
                                            val request: String? = null,
                                        )
                                    }
                                }
                            }
                        }

                        @Serializable
                        class VideoPrimaryInfoRenderer(
                            @SerialName("title")
                            val title: Title? = null,
                            @SerialName("viewCount")
                            val viewCount: ViewCount? = null,
                            @SerialName("dateText")
                            val dateText: DateText? = null,
                        ) {
                            @Serializable
                            class Title(
                                @SerialName("runs")
                                val runs: List<Run>? = null,
                            ) {
                                @Serializable
                                class Run(
                                    @SerialName("text")
                                    val text: String? = null,
                                )
                            }

                            @Serializable
                            class ViewCount(
                                @SerialName("videoViewCountRenderer")
                                val videoViewCountRenderer: VideoViewCountRenderer? = null,
                            ) {
                                @Serializable
                                class VideoViewCountRenderer(
                                    @SerialName("viewCount")
                                    val viewCount: VideoViewCountRenderer.ViewCount? = null,
                                ) {
                                    @Serializable
                                    class ViewCount(
                                        @SerialName("simpleText")
                                        val simpleText: String? = null,
                                    )
                                }
                            }

                            @Serializable
                            class DateText(
                                @SerialName("simpleText")
                                val simpleText: String? = null,
                            )
                        }

                        @Serializable
                        class VideoSecondaryInfoRenderer(
                            @SerialName("owner")
                            val owner: Owner? = null,
                            @SerialName("attributedDescription")
                            val attributedDescription: AttributedDescription? = null,
                        ) {
                            @Serializable
                            class AttributedDescription(
                                @SerialName("content")
                                val content: String? = null,
                            )

                            @Serializable
                            class Owner(
                                @SerialName("videoOwnerRenderer")
                                val videoOwnerRenderer: VideoOwnerRenderer? = null,
                            ) {
                                @Serializable
                                class VideoOwnerRenderer(
                                    @SerialName("thumbnail")
                                    val thumbnail: Thumbnail? = null,
                                    @SerialName("subscriberCountText")
                                    val subscriberCountText: SubscriberCountText? = null,
                                    @SerialName("title")
                                    val title: Title? = null,
                                    @SerialName("navigationEndpoint")
                                    val navigationEndpoint: NavigationEndpoint? = null,
                                ) {
                                    @Serializable
                                    class Thumbnail(
                                        @SerialName("thumbnails")
                                        val thumbnails: List<com.metrolist.innertube.models.Thumbnail>? = null,
                                    )

                                    @Serializable
                                    class SubscriberCountText(
                                        @SerialName("simpleText")
                                        val simpleText: String? = null,
                                    )

                                    @Serializable
                                    class Title(
                                        @SerialName("runs")
                                        val runs: List<Run>? = null,
                                    ) {
                                        @Serializable
                                        class Run(
                                            @SerialName("text")
                                            val text: String? = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}