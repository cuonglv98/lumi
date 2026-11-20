@file:OptIn(UnstableApi::class)

package dev.lumi.player.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.lumi.player.extractor.OkHttpDownloader
import dev.lumi.player.extractor.PlaybackPlan
import dev.lumi.player.extractor.StreamPart
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Biến một [MediaItem] (đã được gắn [PlaybackPlan] trong tag) thành MediaSource cho ExoPlayer.
 *
 * Điểm mấu chốt: YouTube chất lượng >360p trả video và audio thành 2 luồng riêng,
 * nên ta dùng [MergingMediaSource] để phát đồng thời 2 luồng như một video.
 */
class LumiMediaSourceFactory : MediaSource.Factory {

    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(OkHttpDownloader.instance.client)
    ) { spec ->
        // Chỉnh header/kiểu request cho khớp với "client" YouTube đã xin link
        YoutubeRequestAdapter.adapt(spec)
    }

    private var drmProvider: DrmSessionManagerProvider? = null
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) =
        apply { drmProvider = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) =
        apply { loadErrorPolicy = policy }

    override fun getSupportedTypes() =
        intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val plan = mediaItem.localConfiguration?.tag as? PlaybackPlan
            ?: PlaybackPlan.Failed("Chưa lấy được thông tin luồng phát")

        return when (plan) {
            is PlaybackPlan.Split -> {
                // MediaItem gốc (có tiêu đề, ảnh...) gắn vào nguồn đầu tiên để notification hiển thị đúng
                val sources = buildList {
                    plan.video?.let { add(partSource(it, mediaItem)) }
                    plan.audio?.let {
                        add(partSource(it, if (isEmpty()) mediaItem else MediaItem.fromUri(it.url)))
                    }
                }
                if (sources.size == 1) sources[0]
                else MergingMediaSource(
                    /* adjustPeriodTimeOffsets = */ true,
                    /* clipDurations = */ true,
                    *sources.toTypedArray(),
                )
            }
            is PlaybackPlan.Muxed -> partSource(plan.video, mediaItem)
            is PlaybackPlan.Hls -> HlsMediaSource.Factory(dataSourceFactory).configure()
                .createMediaSource(
                    mediaItem.buildUpon().setUri(plan.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                )
            is PlaybackPlan.Dash -> DashMediaSource.Factory(dataSourceFactory).configure()
                .createMediaSource(
                    mediaItem.buildUpon().setUri(plan.url).setMimeType(MimeTypes.APPLICATION_MPD).build()
                )
            is PlaybackPlan.Failed -> FailedMediaSource(mediaItem, IOException(plan.message))
        }
    }

    private fun partSource(part: StreamPart, mediaItem: MediaItem): MediaSource {
        val manifestXml = part.dashManifest
        return if (manifestXml != null) {
            val manifest = DashManifestParser().parse(
                Uri.parse(part.url),
                ByteArrayInputStream(manifestXml.toByteArray(Charsets.UTF_8)),
            )
            DashMediaSource.Factory(dataSourceFactory).configure()
                .createMediaSource(manifest, mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).configure()
                .createMediaSource(mediaItem.buildUpon().setUri(part.url).build())
        }
    }

    private fun <F : MediaSource.Factory> F.configure(): F = apply {
        drmProvider?.let { setDrmSessionManagerProvider(it) }
        loadErrorPolicy?.let { setLoadErrorHandlingPolicy(it) }
    }
}

/**
 * Nguồn "hỏng" có chủ đích: để lỗi lấy link hiện lên trình phát thay vì im lặng.
 *
 * Lỗi được ném ở cấp *period* (khi thật sự phát tới video này), không ném ở cấp source —
 * nếu không, 1 video lỗi nằm trong hàng chờ sẽ làm hỏng luôn video đang phát.
 */
private class FailedMediaSource(
    private val item: MediaItem,
    private val error: IOException,
) : BaseMediaSource() {
    override fun getMediaItem() = item
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(
            SinglePeriodTimeline(
                /* durationUs = */ C.TIME_UNSET,
                /* isSeekable = */ false,
                /* isDynamic = */ false,
                /* useLiveConfiguration = */ false,
                /* manifest = */ null,
                item,
            )
        )
    }
    override fun maybeThrowSourceInfoRefreshError() {}
    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = FailedMediaPeriod(error)
    override fun releasePeriod(mediaPeriod: MediaPeriod) {}
    override fun releaseSourceInternal() {}
}

/** Period không bao giờ chuẩn bị xong; ExoPlayer hỏi lỗi khi tới lượt phát thì ném [error]. */
private class FailedMediaPeriod(private val error: IOException) : MediaPeriod {
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {}
    override fun maybeThrowPrepareError() = throw error
    override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long = positionUs
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {}
    override fun readDiscontinuity(): Long = C.TIME_UNSET
    override fun seekToUs(positionUs: Long): Long = positionUs
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
    override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE
    override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
    override fun isLoading(): Boolean = false
    override fun reevaluateBuffer(positionUs: Long) {}
}
