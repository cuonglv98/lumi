package dev.lumi.player.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand

/**
 * Các khoá dùng chung giữa giao diện và [PlaybackService].
 * Thông tin phụ được gửi qua MediaMetadata.extras vì đó là thứ đi qua được MediaSession.
 */
object LumiKeys {
    /** Video được thêm tự động (tự phát), không phải do người dùng chọn. */
    const val AUTOPLAY = "lumi.autoplay"
    /** Danh sách nhãn chất lượng có sẵn, vd ["1080p60", "720p", ...]. */
    const val QUALITY_LABELS = "lumi.quality.labels"
    /** Vị trí trong [QUALITY_LABELS] đang được phát. */
    const val QUALITY_SELECTED = "lumi.quality.selected"

    // Thông tin hiển thị ở trang xem video
    const val VIEWS = "lumi.views"
    const val LIKES = "lumi.likes"
    const val UPLOADED = "lumi.uploaded"
    const val CHANNEL_AVATAR = "lumi.channel.avatar"
    const val SUBSCRIBERS = "lumi.subscribers"
    const val DESCRIPTION = "lumi.description"
    const val DURATION = "lumi.duration"
    const val CHANNEL_URL = "lumi.channel.url"
    const val UPLOADED_AT = "lumi.uploaded.at"
    /** Danh sách video liên quan (ArrayList<Bundle>, xem VideoItemCodec). */
    const val RELATED = "lumi.related"
}

/** Lệnh tuỳ chỉnh giao diện gửi sang service (ngoài các lệnh play/pause/seek có sẵn). */
object LumiCommands {
    const val SET_QUALITY = "lumi.SET_QUALITY"
    const val ARG_INDEX = "index"
    val setQuality = SessionCommand(SET_QUALITY, Bundle.EMPTY)
}

val MediaItem.isAutoplay: Boolean
    get() = mediaMetadata.extras?.getBoolean(LumiKeys.AUTOPLAY) == true

/**
 * Vị trí chèn khi "Thêm vào hàng chờ": sau các video người dùng tự thêm,
 * nhưng trước các video tự phát ở cuối (giống cách YouTube làm).
 */
fun Player.queueInsertIndex(): Int {
    var index = mediaItemCount
    while (index - 1 > currentMediaItemIndex && getMediaItemAt(index - 1).isAutoplay) index--
    return index
}
