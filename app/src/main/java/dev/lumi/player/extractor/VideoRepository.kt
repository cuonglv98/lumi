package dev.lumi.player.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Một video trong danh sách — model riêng của app, không lộ kiểu của thư viện ra UI. */
data class VideoItem(
    val url: String,
    val title: String,
    val channel: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val uploadedText: String?,
    val thumbnailUrl: String?,
    val channelAvatarUrl: String?,
    val isLive: Boolean,
    /** Link trang kênh — để mở trang kênh / theo dõi. */
    val channelUrl: String? = null,
    /** Thời điểm đăng (epoch ms) — để sắp xếp feed kênh đăng ký và hiện "x giờ trước" luôn đúng. */
    val uploadedAt: Long? = null,
)

/** Một trang danh sách (kết quả tìm kiếm / thịnh hành) + "con trỏ" để tải trang tiếp theo. */
class FeedPage(
    val items: List<VideoItem>,
    internal val extractor: ListExtractor<out InfoItem>,
    internal val nextPage: Page?,
    /** Kênh xuất hiện trong kết quả tìm kiếm (hiện thành thẻ kênh). */
    val channels: List<ChannelResult> = emptyList(),
    /** Nếu YouTube chặn từ khoá gốc và app phải tìm bằng cách diễn đạt khác: từ khoá đã dùng. */
    val rewrittenQuery: String? = null,
) {
    val hasMore: Boolean get() = Page.isValid(nextPage)
}

data class ChannelResult(val summary: ChannelSummary, val subscriberCount: Long, val videoCount: Long)

/** YouTube ẩn toàn bộ kết quả cho từ khoá này (cho rằng có thể không phù hợp). */
class SearchBlockedException(val query: String) :
    Exception("YouTube ẩn kết quả cho «$query» vì cho rằng có thể không phù hợp. Thử thêm từ khác, ví dụ «$query video».")

/** Một mục thịnh hành có sẵn trên YouTube (Âm nhạc, Trò chơi...). */
data class Kiosk(val id: String, val label: String)

object VideoRepository {

    private val youtube get() = ServiceList.YouTube

    /**
     * Tìm kiếm. Với một số từ khoá (vd "faptv"), YouTube trả về khung cảnh báo
     * "These results may be inappropriate" thay vì kết quả — nhưng cùng nội dung diễn đạt
     * khác đi (vd "faptv video") lại có kết quả. Nên ta tự thử vài cách diễn đạt.
     */
    suspend fun search(query: String): FeedPage = withContext(Dispatchers.IO) {
        val attempts = listOf(query, "$query video", "video $query", "$query youtube")
        for ((index, attempt) in attempts.withIndex()) {
            try {
                val page = searchOnce(attempt)
                return@withContext if (index == 0) page else page.withRewrite(attempt)
            } catch (e: SearchExtractor.NothingFoundException) {
                // Không có kết quả / bị chặn → thử cách diễn đạt tiếp theo
            }
        }
        throw SearchBlockedException(query)
    }

    private fun searchOnce(query: String): FeedPage {
        val extractor = youtube.getSearchExtractor(query)
        extractor.fetchPage()
        val page = extractor.initialPage
        return FeedPage(
            items = page.items.mapNotNull(::toVideoItem),
            extractor = extractor,
            nextPage = page.nextPage,
            channels = page.items.filterIsInstance<ChannelInfoItem>().map {
                ChannelResult(
                    ChannelSummary(it.url, it.name, pickImage(it.thumbnails, targetWidth = 176)),
                    it.subscriberCount,
                    it.streamCount,
                )
            },
        )
    }

    private fun FeedPage.withRewrite(query: String) =
        FeedPage(items, extractor, nextPage, channels, rewrittenQuery = query)

    suspend fun kiosk(id: String): FeedPage = withContext(Dispatchers.IO) {
        val extractor = youtube.kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        val page = extractor.initialPage
        FeedPage(page.items.mapNotNull(::toVideoItem), extractor, page.nextPage)
    }

    suspend fun loadMore(previous: FeedPage): FeedPage = withContext(Dispatchers.IO) {
        val page = previous.extractor.getPage(previous.nextPage)
        FeedPage(page.items.mapNotNull(::toVideoItem), previous.extractor, page.nextPage)
    }

    /** Gợi ý từ khoá khi đang gõ tìm kiếm. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        youtube.suggestionExtractor.suggestionList(query).take(10)
    }

    /**
     * Các mục thịnh hành mà phiên bản NewPipeExtractor hiện tại hỗ trợ.
     * Đọc lúc chạy (thay vì ghi cứng ID) để không vỡ khi thư viện đổi tên mục.
     */
    fun kiosks(): List<Kiosk> {
        val ids = try {
            youtube.kioskList.availableKiosks
        } catch (e: Exception) {
            emptySet()
        }
        return ids.map { Kiosk(it, kioskLabel(it)) }
            .sortedBy { KIOSK_ORDER.indexOfFirst { key -> it.id.contains(key, ignoreCase = true) }.let { i -> if (i < 0) 99 else i } }
    }

    private val KIOSK_ORDER = listOf("music", "gaming", "live", "podcast", "movie", "trending")

    private fun kioskLabel(id: String): String = when {
        id.contains("music", true) -> "Âm nhạc"
        id.contains("gaming", true) -> "Trò chơi"
        id.contains("live", true) -> "Trực tiếp"
        id.contains("podcast", true) -> "Podcast"
        id.contains("movie", true) -> "Phim"
        id.contains("trending", true) -> "Thịnh hành"
        else -> id
    }

    internal fun toVideoItem(item: Any): VideoItem? {
        if (item !is StreamInfoItem) return null // bỏ qua kênh / playlist
        return VideoItem(
            url = item.url,
            title = item.name,
            channel = item.uploaderName.orEmpty(),
            durationSeconds = item.duration,
            viewCount = item.viewCount,
            uploadedText = TimeText.relative(item.uploadDate, item.textualUploadDate),
            thumbnailUrl = pickImage(item.thumbnails, targetWidth = 720),
            channelAvatarUrl = pickImage(item.uploaderAvatars, targetWidth = 88),
            isLive = item.streamType == StreamType.LIVE_STREAM,
            channelUrl = item.uploaderUrl,
            uploadedAt = item.uploadDate?.instant?.toEpochMilli(),
        )
    }

    /** Lấy ID video từ mọi dạng link (youtu.be/..., watch?v=..., shorts/...) để so trùng. */
    fun videoIdOf(url: String): String =
        try {
            youtube.streamLHFactory.getId(url)
        } catch (e: Exception) {
            url
        }

    /** Chọn ảnh có chiều rộng gần [targetWidth] nhất, tránh tải ảnh quá to. */
    fun pickImage(images: List<Image>, targetWidth: Int): String? {
        if (images.isEmpty()) return null
        return images.minByOrNull { img ->
            val w = img.width
            if (w <= 0) Int.MAX_VALUE / 2 else kotlin.math.abs(w - targetWidth)
        }?.url
    }
}
