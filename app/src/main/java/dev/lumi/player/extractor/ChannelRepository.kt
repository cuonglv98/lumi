package dev.lumi.player.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

/** Thông tin ngắn gọn của 1 kênh — đủ để hiển thị và theo dõi. */
data class ChannelSummary(val url: String, val name: String, val avatarUrl: String?)

data class ChannelDetails(
    val summary: ChannelSummary,
    val bannerUrl: String?,
    val subscriberCount: Long,
    val description: String,
    val verified: Boolean,
)

/** Một trang video của kênh + con trỏ trang tiếp theo. */
class ChannelVideosPage(
    val items: List<VideoItem>,
    internal val tab: ListLinkHandler?,
    internal val nextPage: Page?,
) {
    val hasMore: Boolean get() = tab != null && Page.isValid(nextPage)
}

object ChannelRepository {

    private val youtube get() = ServiceList.YouTube

    /** Thông tin kênh + trang video đầu tiên (2 request). */
    suspend fun load(url: String): Pair<ChannelDetails, ChannelVideosPage> = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(youtube, url)
        val summary = ChannelSummary(
            url = info.url,
            name = info.name,
            avatarUrl = VideoRepository.pickImage(info.avatars, targetWidth = 176),
        )
        val details = ChannelDetails(
            summary = summary,
            bannerUrl = VideoRepository.pickImage(info.banners, targetWidth = 1080),
            subscriberCount = info.subscriberCount,
            description = info.description.orEmpty(),
            verified = info.isVerified,
        )
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val page = if (videosTab == null) {
            ChannelVideosPage(emptyList(), null, null)
        } else {
            val tabInfo = ChannelTabInfo.getInfo(youtube, videosTab)
            ChannelVideosPage(tabInfo.relatedItems.toVideos(summary), videosTab, tabInfo.nextPage)
        }
        details to page
    }

    suspend fun moreVideos(previous: ChannelVideosPage, channel: ChannelSummary): ChannelVideosPage =
        withContext(Dispatchers.IO) {
            val tab = previous.tab ?: return@withContext ChannelVideosPage(emptyList(), null, null)
            val next = previous.nextPage ?: return@withContext ChannelVideosPage(emptyList(), null, null)
            val page = ChannelTabInfo.getMoreItems(youtube, tab, next)
            ChannelVideosPage(page.items.toVideos(channel), tab, page.nextPage)
        }

    /**
     * Video mới nhất của kênh, dùng cho tab "Kênh đăng ký".
     * Đọc qua RSS của YouTube (rất nhẹ); nếu lỗi thì đọc trang video của kênh.
     */
    fun latestVideos(channel: ChannelSummary): List<VideoItem> {
        return try {
            FeedInfo.getInfo(youtube, channel.url).relatedItems.toVideos(channel)
        } catch (e: Exception) {
            val info = ChannelInfo.getInfo(youtube, channel.url)
            val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: return emptyList()
            ChannelTabInfo.getInfo(youtube, tab).relatedItems.toVideos(channel)
        }
    }

    /** Lấy tên + ảnh đại diện của kênh (dùng để bổ sung cho kênh nhập từ Takeout). */
    fun summary(url: String): ChannelSummary {
        val info = ChannelInfo.getInfo(youtube, url)
        return ChannelSummary(info.url, info.name, VideoRepository.pickImage(info.avatars, targetWidth = 176))
    }

    /** ID kênh để so trùng (link kênh có nhiều dạng: /channel/UC..., /@ten...). */
    fun channelIdOf(url: String): String =
        try {
            youtube.channelLHFactory.getId(url)
        } catch (e: Exception) {
            url
        }

    fun urlForChannelId(channelId: String) = "https://www.youtube.com/channel/$channelId"

    /** Video trong trang kênh thường thiếu tên/ảnh kênh → điền từ thông tin kênh. */
    private fun List<Any>.toVideos(channel: ChannelSummary): List<VideoItem> =
        mapNotNull(VideoRepository::toVideoItem).map {
            it.copy(
                channel = it.channel.ifEmpty { channel.name },
                channelAvatarUrl = it.channelAvatarUrl ?: channel.avatarUrl,
                channelUrl = it.channelUrl ?: channel.url,
            )
        }
}
