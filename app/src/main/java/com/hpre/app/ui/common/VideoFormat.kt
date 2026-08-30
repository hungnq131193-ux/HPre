package com.hpre.app.ui.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object VideoFormat {
    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return ""
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remaining = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, remaining)
        }
    }

    fun viewCount(views: Long?): String {
        if (views == null || views < 0) return ""
        val (value, unit) = when {
            views >= 999_950_000 -> views / 1_000_000_000.0 to "T"
            views >= 999_950 -> views / 1_000_000.0 to "Tr"
            views >= 1_000 -> views / 1_000.0 to "N"
            else -> return "$views lượt xem"
        }
        val formatter = DecimalFormat(
            "0.#",
            DecimalFormatSymbols(Locale("vi", "VN"))
        )
        return "${formatter.format(value)} $unit lượt xem"
    }

    fun age(
        publishedTimestamp: Long?,
        now: Long,
        locale: Locale = Locale.getDefault()
    ): String {
        if (publishedTimestamp == null || publishedTimestamp > now) return ""
        val elapsed = now - publishedTimestamp
        val minutes = elapsed / 60_000L
        val hours = elapsed / 3_600_000L
        val days = elapsed / 86_400_000L
        if (locale.language != "vi") {
            return when {
                minutes < 1 -> "just now"
                hours < 1 -> "${minutes} ${if (minutes == 1L) "minute" else "minutes"} ago"
                days < 1 -> "${hours} ${if (hours == 1L) "hour" else "hours"} ago"
                days < 7 -> "${days} ${if (days == 1L) "day" else "days"} ago"
                days < 30 -> (days / 7).let { "$it ${if (it == 1L) "week" else "weeks"} ago" }
                days < 365 -> (days / 30).let { "$it ${if (it == 1L) "month" else "months"} ago" }
                else -> (days / 365).let { "$it ${if (it == 1L) "year" else "years"} ago" }
            }
        }
        return when {
            minutes < 1 -> "vừa xong"
            hours < 1 -> "$minutes phút trước"
            days < 1 -> "$hours giờ trước"
            days < 7 -> "$days ngày trước"
            days < 30 -> "${days / 7} tuần trước"
            days < 365 -> "${days / 30} tháng trước"
            else -> "${days / 365} năm trước"
        }
    }
}
