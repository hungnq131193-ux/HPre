package com.hpre.app.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Wraps [base] so that [getResources] resolves against the selected app language while keeping the
 * original context chain intact. Preserving the chain matters because callers walk
 * [ContextWrapper.getBaseContext] to reach the hosting activity.
 */
private class LocalizedContextWrapper(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

/**
 * Applies [language] to the composition immediately, without waiting for an activity recreation or
 * a navigation event.
 *
 * `stringResource` reads [LocalConfiguration] (to invalidate on change) and then
 * `LocalContext.current.resources`. Overriding both makes every `stringResource` call inside
 * [content] recompose and resolve from the localized resource table as soon as [language] changes.
 */
@Composable
fun AppLocaleProvider(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val localized = remember(context, configuration, language) {
        val locale = Locale(language.code)
        val localizedConfiguration = Configuration(configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val localizedResources = context
            .createConfigurationContext(localizedConfiguration)
            .resources
        LocalizedContextWrapper(context, localizedResources) to localizedConfiguration
    }

    // Keeps non-resource formatting (numbers, dates) aligned with the in-app language. Keyed on the
    // language so it does not run on every recomposition.
    SideEffect {
        val locale = Locale(language.code)
        if (Locale.getDefault().language != locale.language) {
            Locale.setDefault(locale)
        }
    }

    CompositionLocalProvider(
        LocalContext provides localized.first,
        LocalConfiguration provides localized.second,
        content = content
    )
}
