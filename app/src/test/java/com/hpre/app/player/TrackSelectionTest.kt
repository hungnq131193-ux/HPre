package com.hpre.app.player

import com.hpre.app.model.AudioStream
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.SubtitleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectionTest {

    private fun info(
        subtitles: List<SubtitleStream> = emptyList(),
        audioLanguages: List<String> = emptyList()
    ) = StreamInfo(
        key = com.hpre.app.model.ContentKey(0, "v"),
        title = "t",
        subtitles = subtitles,
        audioStreams = audioLanguages.map {
            AudioStream(url = "https://x/$it", format = "m4a", bitrate = null, language = it)
        }
    )

    @Test
    fun previous_subtitle_language_is_kept_when_still_available() {
        val subs = listOf(
            SubtitleStream("https://x/en", "en", "vtt"),
            SubtitleStream("https://x/vi", "vi", "vtt")
        )
        val resolved = resolveTrackSelections(info(subtitles = subs), null, "vi", null)
        assertEquals("vi", resolved.subtitleLanguage)
    }

    @Test
    fun subtitle_language_falls_back_to_first_when_previous_missing() {
        val subs = listOf(SubtitleStream("https://x/en", "en", "vtt"))
        val resolved = resolveTrackSelections(info(subtitles = subs), null, "fr", null)
        assertEquals("en", resolved.subtitleLanguage)
    }

    @Test
    fun no_subtitles_means_no_selection() {
        val resolved = resolveTrackSelections(info(), null, "en", null)
        assertNull(resolved.subtitleLanguage)
    }

    @Test
    fun audio_languages_only_listed_for_manifest_streams() {
        val dash = resolveTrackSelections(
            info(audioLanguages = listOf("en", "ja")), PlaybackStreamType.DASH, null, null
        )
        assertEquals(listOf("en", "ja"), dash.audioLanguages)
        val progressive = resolveTrackSelections(
            info(audioLanguages = listOf("en", "ja")), PlaybackStreamType.PROGRESSIVE, null, null
        )
        assertTrue(progressive.audioLanguages.isEmpty())
    }

    @Test
    fun previous_audio_language_dropped_when_not_in_new_stream() {
        val resolved = resolveTrackSelections(
            info(audioLanguages = listOf("en")), PlaybackStreamType.DASH, null, "ja"
        )
        assertNull(resolved.audioLanguage)
    }

    @Test
    fun previous_audio_language_kept_when_still_available() {
        val resolved = resolveTrackSelections(
            info(audioLanguages = listOf("en", "ja")), PlaybackStreamType.DASH, null, "ja"
        )
        assertEquals("ja", resolved.audioLanguage)
    }
}
