package com.hpre.app.extractor

import com.hpre.app.settings.AppLanguage
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Single source of truth for the provider locale used by HPre.
 *
 * The provider decides trending/kiosk content from the requested content country, so the
 * Vietnamese trending feed is obtained by asking upstream for VN content rather than by
 * filtering a global feed on the client. The request language follows the app language
 * picked in Settings so provider messages (e.g. playability reasons) match the UI.
 */
object ExtractorLocalization {
    /** Requested UI/metadata language; follows the user's app language. */
    @Volatile
    var localization: Localization = Localization("vi", "VN")
        private set

    /** Requested content region; drives which trending kiosk the provider returns. */
    val CONTENT_COUNTRY: ContentCountry = ContentCountry("VN")

    fun localizationFor(language: AppLanguage): Localization = when (language) {
        AppLanguage.VIETNAMESE -> Localization("vi", "VN")
        AppLanguage.ENGLISH -> Localization("en", "US")
    }

    /**
     * Points the provider at the user's app language. The extractor reads the preferred
     * localization per request, so this takes effect on the next call without a restart.
     */
    fun applyLanguage(language: AppLanguage) {
        localization = localizationFor(language)
        if (ExtractorBootstrap.isInitialized()) {
            NewPipe.setPreferredLocalization(localization)
        }
    }

    /**
     * Narrow seam over the provider types that accept a forced locale, so the policy is
     * testable without touching NewPipe global static state.
     */
    interface Localizable {
        fun forceLocalization(localization: Localization)
        fun forceContentCountry(contentCountry: ContentCountry)
    }

    fun apply(target: Localizable) {
        target.forceLocalization(localization)
        target.forceContentCountry(CONTENT_COUNTRY)
    }
}
