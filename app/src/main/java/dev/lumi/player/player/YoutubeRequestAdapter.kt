@file:OptIn(UnstableApi::class)

package dev.lumi.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import dev.lumi.player.extractor.OkHttpDownloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Link luồng googlevideo được "ký" cho một client cụ thể (web / visionOS...).
 * Gửi sai User-Agent hoặc sai kiểu request → YouTube trả HTTP 403.
 * Hàm này chỉnh request của ExoPlayer cho khớp với client đã xin link (giống NewPipe làm).
 */
object YoutubeRequestAdapter {

    /** Body "x\0" mà trình phát web của YouTube gửi kèm khi tải luồng. */
    private val WEB_POST_BODY = byteArrayOf(0x78, 0x00)

    fun adapt(spec: DataSpec): DataSpec {
        val url = spec.uri.toString()
        return when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> spec.withAdditionalHeaders(
                mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
            )
            YoutubeParsingHelper.isWebStreamingUrl(url) -> spec.buildUpon()
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(WEB_POST_BODY)
                .build()
                .withAdditionalHeaders(webHeaders)
            else -> spec.withAdditionalHeaders(mapOf("User-Agent" to OkHttpDownloader.USER_AGENT))
        }
    }

    private val webHeaders = mapOf(
        "User-Agent" to OkHttpDownloader.USER_AGENT,
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )
}
