package dev.lumi.player.extractor

import org.schabi.newpipe.extractor.localization.DateWrapper
import java.time.Duration
import java.time.Instant

/** Thời gian tương đối bằng tiếng Việt: "3 giờ trước", "5 tháng trước"... */
object TimeText {

    fun relative(date: DateWrapper?, fallback: String?): String? =
        date?.instant?.let(::relative) ?: fallback

    fun relative(instant: Instant): String {
        val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "vừa xong"
            minutes < 60 -> "$minutes phút trước"
            hours < 24 -> "$hours giờ trước"
            days < 7 -> "$days ngày trước"
            days < 30 -> "${days / 7} tuần trước"
            days < 365 -> "${days / 30} tháng trước"
            else -> "${days / 365} năm trước"
        }
    }
}
