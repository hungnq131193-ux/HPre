package com.hpre.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CountFormatTest {

    private fun en(count: Long) = CountFormat.compact(count, "K", "M", "B", Locale.US)
    private fun vi(count: Long) = CountFormat.compact(count, " N", " Tr", " T", Locale("vi"))

    @Test
    fun below_thousand_is_plain() {
        assertEquals("0", en(0))
        assertEquals("999", en(999))
    }

    @Test
    fun thousands_compact_with_two_significant_decimals() {
        assertEquals("1.2K", en(1_200))
        assertEquals("12.3K", en(12_340))
        assertEquals("152K", en(152_000))
    }

    @Test
    fun millions_and_billions() {
        assertEquals("6.63M", en(6_630_000))
        assertEquals("1.5B", en(1_500_000_000))
    }

    @Test
    fun trailing_zeros_are_trimmed() {
        assertEquals("5M", en(5_000_000))
        assertEquals("10M", en(10_000_000))
    }

    @Test
    fun vietnamese_locale_uses_comma_separator() {
        assertEquals("6,63 Tr", vi(6_630_000))
        assertEquals("1,2 N", vi(1_200))
    }

    @Test
    fun negative_is_empty() {
        assertEquals("", en(-5))
    }
}
