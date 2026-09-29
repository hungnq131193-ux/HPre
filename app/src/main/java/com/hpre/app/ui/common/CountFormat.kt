package com.hpre.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hpre.app.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * YouTube-style compact counts: up to 3 significant digits, no trailing zeros
 * (999 → "999", 1_200 → "1.2K", 6_630_000 → "6.63M", 152_000_000 → "152M").
 */
object CountFormat {

    fun compact(
        count: Long,
        thousandSuffix: String,
        millionSuffix: String,
        billionSuffix: String,
        locale: Locale
    ): String {
        if (count < 0) return ""
        val (divisor, suffix) = when {
            count >= 1_000_000_000L -> 1_000_000_000.0 to billionSuffix
            count >= 1_000_000L -> 1_000_000.0 to millionSuffix
            count >= 1_000L -> 1_000.0 to thousandSuffix
            else -> return count.toString()
        }
        val scaled = count / divisor
        val pattern = when {
            scaled < 10 -> "0.##"
            scaled < 100 -> "0.#"
            else -> "0"
        }
        val format = DecimalFormat(pattern, DecimalFormatSymbols(locale))
        format.isGroupingUsed = false
        return format.format(scaled) + suffix
    }
}

@Composable
fun subscriberCountLabel(count: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formatted = CountFormat.compact(
        count = count,
        thousandSuffix = stringResource(R.string.count_suffix_thousand),
        millionSuffix = stringResource(R.string.count_suffix_million),
        billionSuffix = stringResource(R.string.count_suffix_billion),
        locale = locale
    )
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.subscriber_count, quantity, formatted)
}
