package com.hpre.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFormatTest {
    @Test
    fun duration_formats_minutes_and_hours() {
        assertEquals("0:00", VideoFormat.duration(0))
        assertEquals("2:05", VideoFormat.duration(125))
        assertEquals("1:02:03", VideoFormat.duration(3723))
    }

    @Test
    fun duration_omits_unknown_or_invalid_values() {
        assertEquals("", VideoFormat.duration(null))
        assertEquals("", VideoFormat.duration(-1))
    }

    @Test
    fun view_count_uses_vietnamese_units_and_decimal_comma() {
        assertEquals("999 lượt xem", VideoFormat.viewCount(999))
        assertEquals("1 N lượt xem", VideoFormat.viewCount(1_000))
        assertEquals("1,2 N lượt xem", VideoFormat.viewCount(1_250))
        assertEquals("1 Tr lượt xem", VideoFormat.viewCount(1_000_000))
        assertEquals("1,5 Tr lượt xem", VideoFormat.viewCount(1_500_000))
        assertEquals("2 T lượt xem", VideoFormat.viewCount(2_000_000_000))
    }

    @Test
    fun view_count_promotes_values_that_round_across_a_unit_boundary() {
        assertEquals("1 Tr lượt xem", VideoFormat.viewCount(999_999))
        assertEquals("1 T lượt xem", VideoFormat.viewCount(999_999_999))
    }

    @Test
    fun view_count_omits_unknown_or_invalid_values() {
        assertEquals("", VideoFormat.viewCount(null))
        assertEquals("", VideoFormat.viewCount(-1))
    }

    @Test
    fun age_formats_vietnamese_relative_time() {
        val now = 2_000_000_000_000L
        assertEquals("vừa xong", VideoFormat.age(now, now))
        assertEquals("5 phút trước", VideoFormat.age(now - 5 * 60_000L, now))
        assertEquals("3 giờ trước", VideoFormat.age(now - 3 * 3_600_000L, now))
        assertEquals("6 ngày trước", VideoFormat.age(now - 6 * 86_400_000L, now))
        assertEquals("2 tuần trước", VideoFormat.age(now - 14 * 86_400_000L, now))
        assertEquals("3 tháng trước", VideoFormat.age(now - 90 * 86_400_000L, now))
        assertEquals("2 năm trước", VideoFormat.age(now - 730 * 86_400_000L, now))
    }

    @Test
    fun age_omits_unknown_or_future_values() {
        val now = 2_000_000_000_000L
        assertEquals("", VideoFormat.age(null, now))
        assertEquals("", VideoFormat.age(now + 1, now))
    }
}
