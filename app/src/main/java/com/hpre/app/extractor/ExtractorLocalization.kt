package com.hpre.app.extractor

import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Single source of truth for the provider locale used by HPre.
 *
 * The provider decides trending/kiosk content from the requested content country, so the
 * Vietnamese trending feed is obtained by asking upstream for VN content rather than by
 * filtering a global feed on the client.
 */
object ExtractorLocalization {
    /** Requested UI/metadata language. */
    val LOCALIZATION: Localization = Localization("vi", "VN")

    /** Requested content region; drives which trending kiosk the provider returns. */
    val CONTENT_COUNTRY: ContentCountry = ContentCountry("VN")

    /**
     * Narrow seam over the provider types that accept a forced locale, so the policy is
     * testable without touching NewPipe global static state.
     */
    interface Localizable {
        fun forceLocalization(localization: Localization)
        fun forceContentCountry(contentCountry: ContentCountry)
    }

    fun apply(target: Localizable) {
        target.forceLocalization(LOCALIZATION)
        target.forceContentCountry(CONTENT_COUNTRY)
    }
}
