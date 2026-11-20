package dev.lumi.player.ui

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.extractor.VideoItemCodec
import dev.lumi.player.player.LumiKeys
import dev.lumi.player.player.isAutoplay
import kotlinx.coroutines.delay

/** Ảnh chụp trạng thái player để Compose vẽ lại khi có thay đổi. */
data class PlayerSnapshot(
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val artworkUri: Uri? = null,
    val error: String? = null,
    /** Lỗi do YouTube chặn loại video này (vd video dành cho trẻ em) → gợi ý mở bằng app YouTube. */
    val errorOpenExternally: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    /** Nhãn các mức chất lượng của video hiện tại, vd ["1080p60", "720p"]. Rỗng = không chọn được. */
    val qualities: List<String> = emptyList(),
    val selectedQuality: Int = -1,
    // Thông tin trang xem video (service gửi sang qua MediaMetadata.extras)
    val viewCount: Long = -1,
    val likeCount: Long = -1,
    val uploadedText: String? = null,
    val channelAvatarUrl: String? = null,
    val channelUrl: String? = null,
    val subscriberCount: Long = -1,
    val description: String = "",
    val related: List<VideoItem> = emptyList(),
)

data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String?,
    val artist: String?,
    val artworkUri: Uri?,
    val isAutoplay: Boolean,
) {
    fun asVideo() = VideoItem(
        url = mediaId,
        title = title ?: "Đang tải…",
        channel = artist.orEmpty(),
        durationSeconds = 0,
        viewCount = -1,
        uploadedText = null,
        thumbnailUrl = artworkUri?.toString(),
        channelAvatarUrl = null,
        isLive = false,
    )
}

@Composable
fun rememberPlayerSnapshot(player: Player?): PlayerSnapshot {
    var snapshot by remember(player) { mutableStateOf(player.snapshot()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot = player.snapshot()
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return snapshot
}

/** Tiến độ phát 0..1, cập nhật 2 lần/giây (dùng cho thanh tiến độ của mini-player). */
@Composable
fun rememberPlaybackProgress(player: Player?): Float {
    var progress by remember(player) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (player != null) {
            val duration = player.duration
            progress = if (duration == C.TIME_UNSET || duration <= 0) 0f
            else (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            delay(500)
        }
    }
    return progress
}

private fun Player?.snapshot(): PlayerSnapshot {
    if (this == null) return PlayerSnapshot()
    val meta = mediaMetadata
    val extras = currentMediaItem?.mediaMetadata?.extras ?: Bundle.EMPTY
    return PlayerSnapshot(
        hasMedia = currentMediaItem != null,
        mediaId = currentMediaItem?.mediaId,
        isPlaying = isPlaying,
        isLoading = playbackState == Player.STATE_BUFFERING ||
            (playWhenReady && playbackState == Player.STATE_IDLE && playerError == null && currentMediaItem != null),
        title = meta.title?.toString(),
        artist = meta.artist?.toString(),
        artworkUri = meta.artworkUri,
        error = playerError?.let { describe(it).first },
        errorOpenExternally = playerError?.let { describe(it).second } ?: false,
        queue = (0 until mediaItemCount).map { i ->
            val item = getMediaItemAt(i)
            QueueEntry(
                index = i,
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString(),
                artist = item.mediaMetadata.artist?.toString(),
                artworkUri = item.mediaMetadata.artworkUri,
                isAutoplay = item.isAutoplay,
            )
        },
        currentIndex = currentMediaItemIndex,
        qualities = extras.getStringArrayList(LumiKeys.QUALITY_LABELS).orEmpty(),
        selectedQuality = extras.getInt(LumiKeys.QUALITY_SELECTED, -1),
        viewCount = extras.getLong(LumiKeys.VIEWS, -1),
        likeCount = extras.getLong(LumiKeys.LIKES, -1),
        uploadedText = extras.getString(LumiKeys.UPLOADED),
        channelAvatarUrl = extras.getString(LumiKeys.CHANNEL_AVATAR),
        channelUrl = extras.getString(LumiKeys.CHANNEL_URL),
        subscriberCount = extras.getLong(LumiKeys.SUBSCRIBERS, -1),
        description = extras.getString(LumiKeys.DESCRIPTION).orEmpty(),
        related = BundleCompat.getParcelableArrayList(extras, LumiKeys.RELATED, Bundle::class.java)
            .orEmpty()
            .mapNotNull(VideoItemCodec::fromBundle),
    )
}

/** Diễn giải lỗi bằng tiếng Việt. Trả về (thông điệp, có nên gợi ý "Mở bằng app YouTube"). */
private fun describe(error: PlaybackException): Pair<String, Boolean> {
    var cause: Throwable? = error.cause
    while (cause?.cause != null && cause.message.isNullOrEmpty()) cause = cause.cause
    val detail = cause?.message ?: error.message ?: error.errorCodeName
    return when {
        detail.contains("403") ->
            "YouTube từ chối luồng phát (HTTP 403). Thử lại, hoặc cập nhật NewPipeExtractor lên bản mới hơn." to false
        detail.contains("UNPLAYABLE") || detail.contains("not available", ignoreCase = true) ->
            ("YouTube không cho phát video này ngoài app chính thức — thường là video «dành cho trẻ em». " +
                "NewPipeExtractor hiện chưa hỗ trợ loại này; Lumi sẽ báo khi có bản cập nhật.") to true
        detail.contains("LOGIN_REQUIRED") || detail.contains("age", ignoreCase = true) ->
            "Video giới hạn độ tuổi — YouTube yêu cầu đăng nhập để xem." to true
        else -> detail to false
    }
}
