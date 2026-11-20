package dev.lumi.player.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * Cầu nối giữa NewPipeExtractor và OkHttp.
 * NewPipeExtractor không tự gửi request mạng — nó gọi qua [Downloader] mà app cung cấp.
 */
class OkHttpDownloader private constructor(val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                // YouTube đang nghi ngờ là bot (thường do gọi quá nhiều)
                throw ReCaptchaException("YouTube yêu cầu xác minh (HTTP 429)", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val instance: OkHttpDownloader by lazy {
            OkHttpDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }
}
