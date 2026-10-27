package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
class GetTranscriptResponse(
    val actions: List<Action>?,
) {
    @Serializable
    class Action(
        val updateEngagementPanelAction: UpdateEngagementPanelAction,
    ) {
        @Serializable
        class UpdateEngagementPanelAction(
            val content: Content,
        ) {
            @Serializable
            class Content(
                val transcriptRenderer: TranscriptRenderer,
            ) {
                @Serializable
                class TranscriptRenderer(
                    val body: Body,
                ) {
                    @Serializable
                    class Body(
                        val transcriptBodyRenderer: TranscriptBodyRenderer,
                    ) {
                        @Serializable
                        class TranscriptBodyRenderer(
                            val cueGroups: List<CueGroup>,
                        ) {
                            @Serializable
                            class CueGroup(
                                val transcriptCueGroupRenderer: TranscriptCueGroupRenderer,
                            ) {
                                @Serializable
                                class TranscriptCueGroupRenderer(
                                    val cues: List<Cue>,
                                ) {
                                    @Serializable
                                    class Cue(
                                        val transcriptCueRenderer: TranscriptCueRenderer,
                                    ) {
                                        @Serializable
                                        class TranscriptCueRenderer(
                                            val cue: SimpleText,
                                            val startOffsetMs: Long,
                                            val durationMs: Long,
                                        ) {
                                            @Serializable
                                            class SimpleText(
                                                val simpleText: String,
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
}
