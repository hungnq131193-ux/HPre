package com.hpre.app.extractor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class ExtractorLocalizationTest {

    @Test
    fun default_content_country_is_vietnam() {
        assertEquals("VN", ExtractorLocalization.CONTENT_COUNTRY.countryCode)
    }

    @Test
    fun default_localization_is_vietnamese_in_vietnam() {
        assertEquals("vi", ExtractorLocalization.LOCALIZATION.languageCode)
        assertEquals("VN", ExtractorLocalization.LOCALIZATION.countryCode)
    }

    @Test
    fun localization_code_matches_newpipe_format() {
        assertEquals("vi-VN", ExtractorLocalization.LOCALIZATION.localizationCode)
    }

    @Test
    fun applier_forces_both_localization_and_content_country_on_kiosks() {
        val recorder = RecordingLocalizable()

        ExtractorLocalization.apply(recorder)

        assertEquals(listOf(ExtractorLocalization.LOCALIZATION), recorder.forcedLocalizations)
        assertEquals(listOf(ExtractorLocalization.CONTENT_COUNTRY), recorder.forcedCountries)
    }

    @Test
    fun applier_is_idempotent_per_call_site() {
        val recorder = RecordingLocalizable()

        ExtractorLocalization.apply(recorder)
        ExtractorLocalization.apply(recorder)

        assertEquals(2, recorder.forcedLocalizations.size)
        assertEquals(2, recorder.forcedCountries.size)
        assertEquals(
            setOf(ExtractorLocalization.LOCALIZATION),
            recorder.forcedLocalizations.toSet()
        )
        assertEquals(
            setOf(ExtractorLocalization.CONTENT_COUNTRY),
            recorder.forcedCountries.toSet()
        )
    }

    private class RecordingLocalizable : ExtractorLocalization.Localizable {
        val forcedLocalizations = mutableListOf<Localization>()
        val forcedCountries = mutableListOf<ContentCountry>()

        override fun forceLocalization(localization: Localization) {
            forcedLocalizations += localization
        }

        override fun forceContentCountry(contentCountry: ContentCountry) {
            forcedCountries += contentCountry
        }
    }
}
