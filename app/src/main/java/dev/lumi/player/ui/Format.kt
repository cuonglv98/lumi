package dev.lumi.player.ui

import dev.lumi.player.extractor.TimeText
import java.time.OffsetDateTime
import java.util.Locale

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** 1234 → "1,2 N", 3_400_000 → "3,4 Tr", 1_100_000_000 → "1,1 Tỷ". */
fun formatCompact(value: Long): String {
    if (value < 0) return ""
    fun short(v: Double) = String.format(Locale.forLanguageTag("vi"), "%.1f", v).removeSuffix(",0")
    return when {
        value >= 1_000_000_000 -> "${short(value / 1e9)} Tỷ"
        value >= 1_000_000 -> "${short(value / 1e6)} Tr"
        value >= 1_000 -> "${short(value / 1e3)} N"
        else -> value.toString()
    }
}

fun formatViews(views: Long): String =
    if (views < 0) "" else "${formatCompact(views)} lượt xem"

fun formatSubscribers(count: Long): String =
    if (count < 0) "" else "${formatCompact(count)} người đăng ký"

fun formatDurationMs(ms: Long): String = formatDuration(ms / 1000).ifEmpty { "0:00" }

/** Ngày dạng ISO ("2026-06-02T05:00:25-07:00") → "5 tháng trước"; chữ khác giữ nguyên. */
fun displayDate(text: String?): String? {
    if (text.isNullOrEmpty() || !text[0].isDigit()) return text
    return try {
        TimeText.relative(OffsetDateTime.parse(text).toInstant())
    } catch (e: Exception) {
        text
    }
}
