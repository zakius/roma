package roma.core

import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.StreamResolveException.Reason
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamsTest {
    private val premium = ContentNotAvailableException("This video is a YouTube Music Premium video")

    @Test
    fun lastSpecificReasonWins() {
        val generic = StreamResolveException(Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.", null, null)
        assertEquals("This video is a YouTube Music Premium video", Streams.reason(listOf(premium, premium, generic)))
        val age = StreamResolveException(Reason.AGE_RESTRICTED, "This track is age-restricted.", null, null)
        assertEquals("This track is age-restricted.", Streams.reason(listOf(premium, premium, age)))
    }

    @Test
    fun networkAndEmpty() {
        val offline = StreamResolveException(Reason.NETWORK, "x", java.net.UnknownHostException("youtubei.googleapis.com"), null)
        assertEquals("No connection to YouTube.", Streams.reason(listOf(offline)))
        assertEquals("YouTube gave no playable stream for this song.", Streams.reason(listOf(IllegalStateException("No audio stream for x"))))
    }
}
