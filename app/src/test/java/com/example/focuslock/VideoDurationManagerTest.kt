package com.example.focuslock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDurationManagerTest {
    @Test
    fun parsesLengthSeconds() {
        val html = """<script>var player = {"lengthSeconds":"754"};</script>"""

        assertEquals(754L, VideoDurationManager.parseDurationSeconds(html))
    }

    @Test
    fun fallsBackToApproximateDurationMilliseconds() {
        val html = """{"approxDurationMs":"754001"}"""

        assertEquals(755L, VideoDurationManager.parseDurationSeconds(html))
    }

    @Test
    fun rejectsMissingOrZeroDurations() {
        assertNull(VideoDurationManager.parseDurationSeconds("{}"))
        assertNull(VideoDurationManager.parseDurationSeconds("""{"lengthSeconds":"0"}"""))
    }

    @Test
    fun formatsStandardVideoTimestamps() {
        assertEquals("0:07", VideoDurationManager.formatDuration(7))
        assertEquals("12:34", VideoDurationManager.formatDuration(754))
        assertEquals("1:02:03", VideoDurationManager.formatDuration(3723))
    }
}
