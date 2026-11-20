package dev.lumi.player.extractor

import android.media.MediaCodecList
import android.util.Log
import androidx.core.text.HtmlCompat
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import android.media.MediaFormat as AndroidMediaFormat

/**
 * Một "nguồn" cụ thể để ExoPlayer phát.
 * - [url]: link googlevideo trực tiếp
 * - [dashManifest]: nếu có, đây là manifest DASH do NewPipeExtractor dựng sẵn cho link đó.
 *   Phát qua DASH giúp ExoPlayer tải theo từng đoạn nhỏ (ổn định hơn, ít bị YouTube bóp băng thông).
 */
data class StreamPart(val url: String, val dashManifest: String?)

/** Một mức chất lượng có thể chọn, vd 1080p60. */
data class VideoOption(
    val height: Int,
    val fps: Int,
    val codec: String?,
    internal val stream: VideoStream,
) {
    val label: String get() = if (fps > 30) "${height}p$fps" else "${height}p"
}

/** Kế hoạch phát cho 1 video — được gắn vào MediaItem.tag và đọc lại trong MediaSource.Factory. */
sealed interface PlaybackPlan {
    /**
     * Video-only + audio riêng (cách YouTube phục vụ chất lượng >360p).
     * [options] là mọi mức chất lượng máy phát được; [video] là luồng của mức [selectedIndex].
     */
    data class Split(
        val video: StreamPart?,
        val audio: StreamPart?,
        val options: List<VideoOption>,
        val selectedIndex: Int,
        val durationSeconds: Long,
    ) : PlaybackPlan

    /** Video đã ghép sẵn âm thanh (thường chỉ 360p). */
    data class Muxed(val video: StreamPart) : PlaybackPlan
    data class Hls(val url: String) : PlaybackPlan
    data class Dash(val url: String) : PlaybackPlan
    data class Failed(val message: String) : PlaybackPlan
}

data class ResolvedVideo(
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val plan: PlaybackPlan,
    /** Video liên quan — dùng cho danh sách "Tiếp theo" và tính năng tự phát. */
    val related: List<VideoItem>,
    val details: VideoDetails = VideoDetails(),
)

/** Thông tin hiển thị ở trang xem video. */
data class VideoDetails(
    val viewCount: Long = -1,
    val likeCount: Long = -1,
    val uploadedText: String? = null,
    val channelAvatarUrl: String? = null,
    val subscriberCount: Long = -1,
    val description: String = "",
    val durationSeconds: Long = 0,
    val channelUrl: String? = null,
    val uploadedAt: Long? = null,
)

object StreamResolver {

    private const val TAG = "StreamResolver"

    /**
     * Chạy trên thread nền — có gọi mạng.
     * @param preferredHeight chất lượng mong muốn (vd 1080). Không có thì lấy mức gần nhất thấp hơn.
     */
    fun resolve(url: String, preferredHeight: Int): ResolvedVideo {
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: Exception) {
            Log.e(TAG, "Không lấy được thông tin video", e)
            // Video bị chặn (vd «dành cho trẻ em») vẫn có tiêu đề/kênh/ảnh qua oEmbed — cổng công khai
            // YouTube dành cho việc nhúng video → giao diện + thông báo hiển thị đúng
            val embed = oEmbed(url)
            return ResolvedVideo(
                embed?.optString("title").orEmpty(),
                embed?.optString("author_name").orEmpty(),
                embed?.optString("thumbnail_url")?.ifEmpty { null },
                PlaybackPlan.Failed(e.message ?: e.javaClass.simpleName),
                emptyList(),
                VideoDetails(channelUrl = embed?.optString("author_url")?.ifEmpty { null }),
            )
        }
        return ResolvedVideo(
            title = info.name,
            channel = info.uploaderName.orEmpty(),
            thumbnailUrl = VideoRepository.pickImage(info.thumbnails, targetWidth = 720),
            plan = buildPlan(info, preferredHeight),
            related = info.relatedItems.mapNotNull(VideoRepository::toVideoItem),
            details = VideoDetails(
                viewCount = info.viewCount,
                likeCount = info.likeCount,
                uploadedText = TimeText.relative(info.uploadDate, info.textualUploadDate),
                channelAvatarUrl = VideoRepository.pickImage(info.uploaderAvatars, targetWidth = 88),
                subscriberCount = info.uploaderSubscriberCount,
                description = plainText(info.description.content()).take(5000),
                durationSeconds = info.duration,
                channelUrl = info.uploaderUrl,
                uploadedAt = info.uploadDate?.instant?.toEpochMilli(),
            ),
        )
    }

    private fun buildPlan(info: StreamInfo, preferredHeight: Int): PlaybackPlan {
        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (isLive) {
            if (info.hlsUrl.isNotEmpty()) return PlaybackPlan.Hls(info.hlsUrl)
            if (info.dashMpdUrl.isNotEmpty()) return PlaybackPlan.Dash(info.dashMpdUrl)
        }

        val duration = info.duration
        val audio = pickAudio(info.audioStreams)?.let { toPart(it, duration) }
        val options = videoOptions(info.videoOnlyStreams)

        if (options.isNotEmpty() || audio != null) {
            // Thử mức mong muốn trước; nếu không dựng được luồng thì lùi dần xuống mức thấp hơn
            val start = selectIndex(options, preferredHeight)
            val order = (start until options.size) + (0 until start).reversed()
            for (index in order) {
                val video = toPart(options[index].stream, duration) ?: continue
                return PlaybackPlan.Split(video, audio, options, index, duration)
            }
            if (audio != null) return PlaybackPlan.Split(null, audio, emptyList(), -1, duration)
        }

        info.videoStreams
            .filter { it.isUrl && it.deliveryMethod.isSupported() }
            .maxByOrNull { it.height }
            ?.let { toPart(it, duration) }
            ?.let { return PlaybackPlan.Muxed(it) }

        if (info.hlsUrl.isNotEmpty()) return PlaybackPlan.Hls(info.hlsUrl)
        if (info.dashMpdUrl.isNotEmpty()) return PlaybackPlan.Dash(info.dashMpdUrl)
        return PlaybackPlan.Failed("Không tìm thấy luồng phát phù hợp cho video này")
    }

    private fun oEmbed(videoUrl: String): org.json.JSONObject? = try {
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(videoUrl, "UTF-8"))
            .build()
        OkHttpDownloader.instance.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) org.json.JSONObject(response.body.string()) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Mô tả YouTube là HTML (có link, xuống dòng <br>) → chuyển thành chữ thường. */
    private fun plainText(html: String?): String =
        if (html.isNullOrEmpty()) "" else HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

    /** Mức cao nhất không vượt quá [preferredHeight]; nếu tất cả đều cao hơn thì lấy mức thấp nhất. */
    fun selectIndex(options: List<VideoOption>, preferredHeight: Int): Int {
        if (options.isEmpty()) return -1
        val index = options.indexOfFirst { it.height <= preferredHeight } // options xếp cao → thấp
        return if (index >= 0) index else options.lastIndex
    }

    /**
     * Mỗi độ phân giải giữ 1 luồng tốt nhất. Ưu tiên codec: H.264 (máy nào cũng giải mã phần cứng)
     * → VP9 → AV1, rồi tới fps cao hơn. Bỏ luồng mà máy không giải mã nổi.
     */
    private fun videoOptions(streams: List<VideoStream>): List<VideoOption> =
        streams
            .filter { it.isUrl && it.deliveryMethod.isSupported() && it.height > 0 }
            .filter { DeviceCodecs.canDecode(it.codec, it.width, it.height, it.fps) }
            .groupBy { qualityClass(it.width, it.height) }
            .map { (quality, sameQuality) ->
                quality to sameQuality.maxWith(compareBy({ codecRank(it.codec) }, { it.fps }))
            }
            .sortedByDescending { it.first }
            .map { (quality, stream) -> VideoOption(quality, stream.fps, stream.codec, stream) }

    private val QUALITY_LADDER = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    /**
     * Gọi tên chất lượng theo cách chuẩn: video 1920×960 (tỷ lệ 2:1) hay 1080×1920 (video dọc)
     * đều là "1080p" — không phải 960p hay 1920p.
     */
    private fun qualityClass(width: Int, height: Int): Int {
        if (width <= 0) return height
        val short = minOf(width, height)
        val long = maxOf(width, height)
        val equivalent = maxOf(short, long * 9 / 16)
        return QUALITY_LADDER.minBy { kotlin.math.abs(it - equivalent) }
    }

    private fun codecRank(codec: String?): Int = when {
        codec == null -> 0
        codec.startsWith("avc1") -> 3
        codec.startsWith("vp9") || codec.startsWith("vp09") -> 2
        codec.startsWith("av01") -> 1
        else -> 0
    }

    /**
     * Ưu tiên: bản tiếng gốc (không phải bản lồng tiếng tự động) → định dạng M4A/AAC
     * (thiết bị nào cũng giải mã được) → bitrate cao nhất.
     */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.isUrl && it.deliveryMethod.isSupported() }
            .maxWithOrNull(
                compareBy<AudioStream>(
                    { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL },
                    { it.format == MediaFormat.M4A },
                    { it.averageBitrate },
                )
            )

    private fun DeliveryMethod.isSupported() =
        this == DeliveryMethod.PROGRESSIVE_HTTP || this == DeliveryMethod.DASH

    /** Dựng nguồn phát cho 1 luồng. Có thể gọi mạng (luồng OTF) — chỉ gọi trên thread nền. */
    fun toPart(stream: Stream, durationSeconds: Long): StreamPart? {
        val url = stream.content
        val itag = stream.itagItem
        val manifest = try {
            when {
                itag == null -> null
                stream.deliveryMethod == DeliveryMethod.DASH ->
                    YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(url, itag, durationSeconds)
                durationSeconds > 0 ->
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, durationSeconds)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không dựng được DASH manifest, dùng phát trực tiếp", e)
            null
        }
        // Luồng OTF bắt buộc phải có manifest mới phát được
        if (manifest == null && stream.deliveryMethod == DeliveryMethod.DASH) return null
        return StreamPart(url, manifest)
    }
}

/** Hỏi Android xem máy có bộ giải mã cho codec + độ phân giải này không. */
private object DeviceCodecs {
    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }
    private val cache = HashMap<String, Boolean>()

    @Synchronized
    fun canDecode(codec: String?, width: Int, height: Int, fps: Int): Boolean {
        val mime = when {
            codec == null -> return true
            codec.startsWith("avc1") -> AndroidMediaFormat.MIMETYPE_VIDEO_AVC
            codec.startsWith("vp9") || codec.startsWith("vp09") -> AndroidMediaFormat.MIMETYPE_VIDEO_VP9
            codec.startsWith("av01") -> AndroidMediaFormat.MIMETYPE_VIDEO_AV1
            else -> return true
        }
        val w = if (width > 0) width else height * 16 / 9
        val key = "$mime:${w}x$height@$fps"
        return cache.getOrPut(key) {
            val format = AndroidMediaFormat.createVideoFormat(mime, w, height)
            if (fps > 0) format.setInteger(AndroidMediaFormat.KEY_FRAME_RATE, fps)
            try {
                codecList.findDecoderForFormat(format) != null
            } catch (e: Exception) {
                true
            }
        }
    }
}
