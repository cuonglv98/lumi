package dev.lumi.player.extractor

import android.os.Bundle
import org.json.JSONObject

/**
 * Đổi [VideoItem] sang Bundle (để gửi qua MediaSession tới giao diện)
 * và JSON (để lưu lịch sử xem xuống máy).
 */
object VideoItemCodec {

    fun toBundle(v: VideoItem) = Bundle().apply {
        putString("url", v.url)
        putString("title", v.title)
        putString("channel", v.channel)
        putLong("duration", v.durationSeconds)
        putLong("views", v.viewCount)
        putString("uploaded", v.uploadedText)
        putString("thumb", v.thumbnailUrl)
        putString("avatar", v.channelAvatarUrl)
        putBoolean("live", v.isLive)
        putString("channelUrl", v.channelUrl)
        v.uploadedAt?.let { putLong("at", it) }
    }

    fun fromBundle(b: Bundle): VideoItem? {
        val url = b.getString("url") ?: return null
        return VideoItem(
            url = url,
            title = b.getString("title").orEmpty(),
            channel = b.getString("channel").orEmpty(),
            durationSeconds = b.getLong("duration"),
            viewCount = b.getLong("views", -1),
            uploadedText = b.getString("uploaded"),
            thumbnailUrl = b.getString("thumb"),
            channelAvatarUrl = b.getString("avatar"),
            isLive = b.getBoolean("live"),
            channelUrl = b.getString("channelUrl"),
            uploadedAt = if (b.containsKey("at")) b.getLong("at") else null,
        )
    }

    fun toJson(v: VideoItem): JSONObject = JSONObject().apply {
        put("url", v.url)
        put("title", v.title)
        put("channel", v.channel)
        put("duration", v.durationSeconds)
        put("views", v.viewCount)
        putOpt("uploaded", v.uploadedText)
        putOpt("thumb", v.thumbnailUrl)
        putOpt("avatar", v.channelAvatarUrl)
        put("live", v.isLive)
        putOpt("channelUrl", v.channelUrl)
        putOpt("at", v.uploadedAt)
    }

    fun fromJson(o: JSONObject): VideoItem? {
        val url = o.optString("url")
        if (url.isEmpty()) return null
        return VideoItem(
            url = url,
            title = o.optString("title"),
            channel = o.optString("channel"),
            durationSeconds = o.optLong("duration"),
            viewCount = o.optLong("views", -1),
            uploadedText = o.optString("uploaded").ifEmpty { null },
            thumbnailUrl = o.optString("thumb").ifEmpty { null },
            channelAvatarUrl = o.optString("avatar").ifEmpty { null },
            isLive = o.optBoolean("live"),
            channelUrl = o.optString("channelUrl").ifEmpty { null },
            uploadedAt = if (o.has("at")) o.optLong("at") else null,
        )
    }
}
