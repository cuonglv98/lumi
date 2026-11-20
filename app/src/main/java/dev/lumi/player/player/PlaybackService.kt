@file:OptIn(UnstableApi::class)

package dev.lumi.player.player

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import dev.lumi.player.extractor.OkHttpDownloader
import dev.lumi.player.extractor.PlaybackPlan
import dev.lumi.player.extractor.StreamResolver
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.extractor.VideoItemCodec
import dev.lumi.player.history.WatchHistory
import dev.lumi.player.extractor.VideoRepository.videoIdOf
import dev.lumi.player.ui.MainActivity
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Service giữ trình phát. Đây là lý do app vẫn phát được khi tắt màn hình:
 *
 * - Activity (giao diện) có thể bị dừng bất cứ lúc nào khi tắt màn hình / chuyển app,
 *   nhưng [MediaSessionService] chạy dạng *foreground service* nên hệ thống không giết nó.
 * - Media3 tự tạo notification điều khiển (play/pause/tua/bài trước/bài sau) + điều khiển
 *   trên màn hình khoá, tai nghe Bluetooth, Android Auto...
 *
 * Service cũng lo hàng chờ: tự phát video liên quan, bỏ qua video lỗi, đổi chất lượng.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var settings: LumiSettings
    private lateinit var resolverExecutor: ListeningExecutorService
    private lateinit var mainExecutor: Executor

    /** Video liên quan của từng video đã lấy thông tin (videoId → danh sách). */
    private val relatedCache = ConcurrentHashMap<String, List<VideoItem>>()

    /** Video đã phát trong phiên này — để tự phát không lặp lại bài cũ. */
    private val history = LinkedHashSet<String>()

    /** Đang tìm video tự phát cho videoId này (tránh tìm trùng nhiều lần). */
    private var autoplayPendingFor: String? = null
    private var consecutiveErrorSkips = 0

    /** Video đã được lấy lại link sau lỗi 403 (mỗi lần phát ổn định lại thì cho phép lấy lại tiếp). */
    private var refreshedAfterErrorFor: String? = null

    // Giữ tham chiếu mạnh: SharedPreferences chỉ giữ listener bằng weak reference
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LumiSettings.KEY_AUTOPLAY) onAutoplaySettingChanged()
    }

    override fun onCreate() {
        super.onCreate()
        settings = LumiSettings(this)
        resolverExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
        mainExecutor = ContextCompat.getMainExecutor(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(LumiMediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true, // tự dừng khi có cuộc gọi / app khác phát nhạc
            )
            .setHandleAudioBecomingNoisy(true) // rút tai nghe -> tạm dừng
            .setWakeMode(C.WAKE_MODE_NETWORK)  // giữ CPU + Wi-Fi khi tắt màn hình
            .build()
        player.addListener(QueueListener())

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        settings.prefs.registerOnSharedPreferenceChangeListener(settingsListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        session?.run {
            player.release()
            release()
        }
        session = null
        resolverExecutor.shutdownNow()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        /** Cho phép giao diện gửi lệnh tuỳ chỉnh "đổi chất lượng". */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(LumiCommands.setQuality)
                .build()
            return MediaSession.ConnectionResult.accept(
                commands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == LumiCommands.SET_QUALITY) {
                changeQuality(args.getInt(LumiCommands.ARG_INDEX, -1))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /**
         * UI chỉ gửi sang "link video + tiêu đề". Tại đây (trong service, thread nền)
         * ta mới gọi NewPipeExtractor để lấy link luồng thật và gắn vào MediaItem.
         * Áp dụng cho cả phát ngay, thêm vào hàng chờ và thay thế (thử lại).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            resolverExecutor.submit(Callable { mediaItems.map(::resolve).toMutableList() })
    }

    private inner class QueueListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                player.currentMediaItem?.let { rememberPlayed(videoIdOf(it.mediaId)) }
                maybeQueueRelated()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.let(::recordHistory)
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_READY
            ) {
                consecutiveErrorSkips = 0
                refreshedAfterErrorFor = null
            }
        }

        /**
         * - Link bị YouTube thu hồi giữa chừng (HTTP 403) → lấy link mới, phát tiếp đúng chỗ.
         * - Lỗi khác, hoặc lấy lại vẫn hỏng → chuyển sang video kế tiếp trong hàng chờ.
         */
        override fun onPlayerError(error: PlaybackException) {
            val current = player.currentMediaItem
            if (current != null && error.isHttpForbidden() && refreshedAfterErrorFor != current.mediaId) {
                refreshedAfterErrorFor = current.mediaId
                Log.w(TAG, "Link bị từ chối (403), đang lấy link mới…")
                refreshCurrent()
                return
            }
            // Video YouTube không cho lấy luồng (vd «dành cho trẻ em") → giao diện phát bằng
            // trình phát nhúng, nên KHÔNG tự bỏ qua; hết video nhúng giao diện sẽ chuyển bài.
            if (error.isEmbedOnly()) return
            if (player.hasNextMediaItem() && consecutiveErrorSkips < MAX_ERROR_SKIPS) {
                consecutiveErrorSkips++
                Log.w(TAG, "Bỏ qua video lỗi: ${error.message}")
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /** Ghi video vừa bắt đầu phát vào lịch sử (kèm video liên quan để làm đề xuất). */
    private fun recordHistory(item: MediaItem) {
        if (item.localConfiguration?.tag == null) return // chưa lấy xong thông tin
        val meta = item.mediaMetadata
        val extras = meta.extras ?: Bundle.EMPTY
        val video = VideoItem(
            url = item.mediaId,
            title = meta.title?.toString() ?: return,
            channel = meta.artist?.toString().orEmpty(),
            durationSeconds = extras.getLong(LumiKeys.DURATION),
            viewCount = extras.getLong(LumiKeys.VIEWS, -1),
            uploadedText = extras.getString(LumiKeys.UPLOADED),
            thumbnailUrl = meta.artworkUri?.toString(),
            channelAvatarUrl = extras.getString(LumiKeys.CHANNEL_AVATAR),
            isLive = false,
            channelUrl = extras.getString(LumiKeys.CHANNEL_URL),
            uploadedAt = if (extras.containsKey(LumiKeys.UPLOADED_AT)) extras.getLong(LumiKeys.UPLOADED_AT) else null,
        )
        WatchHistory.record(video, relatedCache[videoIdOf(item.mediaId)].orEmpty())
    }

    // ---------------------------------------------------------------- Lấy luồng phát

    /** Lấy lại link luồng cho video hiện tại rồi phát tiếp từ vị trí cũ. */
    private fun refreshCurrent() {
        val item = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val request = MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build()
        resolverExecutor.execute {
            val fresh = resolve(request)
            mainExecutor.execute {
                if (session == null || player.currentMediaItem?.mediaId != item.mediaId) return@execute
                player.replaceMediaItem(index, fresh)
                player.seekTo(index, position)
                player.prepare()
                player.play()
            }
        }
    }

    private fun resolve(item: MediaItem): MediaItem {
        val resolved = StreamResolver.resolve(item.mediaId, settings.defaultQuality)
        relatedCache[videoIdOf(item.mediaId)] = resolved.related

        val artworkUrl = item.mediaMetadata.artworkUri?.toString() ?: resolved.thumbnailUrl
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY)
        putQualityExtras(extras, resolved.plan)
        resolved.details.let { d ->
            extras.putLong(LumiKeys.VIEWS, d.viewCount)
            extras.putLong(LumiKeys.LIKES, d.likeCount)
            extras.putString(LumiKeys.UPLOADED, d.uploadedText)
            extras.putString(LumiKeys.CHANNEL_AVATAR, d.channelAvatarUrl)
            extras.putLong(LumiKeys.SUBSCRIBERS, d.subscriberCount)
            extras.putString(LumiKeys.DESCRIPTION, d.description)
            extras.putLong(LumiKeys.DURATION, d.durationSeconds)
            extras.putString(LumiKeys.CHANNEL_URL, d.channelUrl)
            d.uploadedAt?.let { extras.putLong(LumiKeys.UPLOADED_AT, it) }
        }
        extras.putParcelableArrayList(
            LumiKeys.RELATED,
            ArrayList(resolved.related.take(MAX_RELATED_SHOWN).map(VideoItemCodec::toBundle)),
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(resolved.title.ifEmpty { null })
            .setArtist(resolved.channel.ifEmpty { null })
            .setArtworkUri(resolved.thumbnailUrl?.toUri())
            .populate(item.mediaMetadata) // thông tin UI gửi sang được ưu tiên
            .setExtras(extras)
            .apply {
                // Tải sẵn ảnh bìa: PlayerView hiện ảnh này khi chỉ nghe âm thanh, màn hình khoá cũng dùng
                downloadArtwork(artworkUrl)?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()
        return item.buildUpon()
            .setUri(uniqueUri(item.mediaId))
            .setTag(resolved.plan)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * URI chỉ là "nhãn" (luồng thật nằm trong tag). Thêm chuỗi ngẫu nhiên để ExoPlayer luôn coi
     * đây là nguồn mới khi thay thế (thử lại / đổi chất lượng), thay vì giữ nguồn cũ.
     */
    private fun uniqueUri(mediaId: String) = "$mediaId#${UUID.randomUUID()}"

    private fun putQualityExtras(extras: Bundle, plan: PlaybackPlan) {
        val split = plan as? PlaybackPlan.Split
        if (split == null || split.options.isEmpty()) {
            extras.remove(LumiKeys.QUALITY_LABELS)
            extras.remove(LumiKeys.QUALITY_SELECTED)
            return
        }
        extras.putStringArrayList(LumiKeys.QUALITY_LABELS, ArrayList(split.options.map { it.label }))
        extras.putInt(LumiKeys.QUALITY_SELECTED, split.selectedIndex)
    }

    private fun downloadArtwork(url: String?): ByteArray? {
        if (url.isNullOrEmpty()) return null
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            OkHttpDownloader.instance.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Đổi chất lượng

    /**
     * Dựng lại nguồn phát của video hiện tại với mức chất lượng khác, rồi tua về đúng chỗ đang xem.
     * (Mỗi mức là 1 luồng googlevideo riêng, nên phải thay nguồn chứ không chỉ đổi track.)
     */
    private fun changeQuality(optionIndex: Int) {
        val item = player.currentMediaItem ?: return
        val plan = item.localConfiguration?.tag as? PlaybackPlan.Split ?: return
        if (optionIndex == plan.selectedIndex) return
        val option = plan.options.getOrNull(optionIndex) ?: return

        resolverExecutor.execute {
            val part = StreamResolver.toPart(option.stream, plan.durationSeconds) ?: return@execute
            mainExecutor.execute {
                val index = player.currentMediaItemIndex
                if (session == null || player.currentMediaItem?.mediaId != item.mediaId) return@execute
                val position = player.currentPosition
                val current = player.currentMediaItem ?: return@execute
                val newPlan = plan.copy(video = part, selectedIndex = optionIndex)
                val extras = Bundle(current.mediaMetadata.extras ?: Bundle.EMPTY)
                putQualityExtras(extras, newPlan)
                val newItem = current.buildUpon()
                    .setUri(uniqueUri(current.mediaId))
                    .setTag(newPlan)
                    .setMediaMetadata(current.mediaMetadata.buildUpon().setExtras(extras).build())
                    .build()
                player.replaceMediaItem(index, newItem)
                player.seekTo(index, position)
            }
        }
    }

    // ---------------------------------------------------------------- Tự phát

    private fun rememberPlayed(videoId: String) {
        history.remove(videoId)
        history.add(videoId)
        while (history.size > MAX_HISTORY) history.remove(history.first())
    }

    /**
     * Khi đang phát video cuối hàng chờ: lấy video liên quan chưa xem và thêm vào cuối.
     * Làm ngay từ đầu video (không đợi hết) để chuyển bài liền mạch, kể cả khi tắt màn hình.
     */
    private fun maybeQueueRelated() {
        if (!settings.autoplay) return
        val current = player.currentMediaItem ?: return
        if (player.currentMediaItemIndex != player.mediaItemCount - 1) return
        val currentId = videoIdOf(current.mediaId)
        if (autoplayPendingFor == currentId) return
        autoplayPendingFor = currentId

        val exclude = HashSet(history)
        for (i in 0 until player.mediaItemCount) exclude += videoIdOf(player.getMediaItemAt(i).mediaId)

        resolverExecutor.execute {
            val related = relatedCache[currentId]
                ?: StreamResolver.resolve(current.mediaId, settings.defaultQuality).related
            val next = related.firstOrNull { !it.isLive && videoIdOf(it.url) !in exclude }
            val nextItem = next?.let { resolve(autoplayItem(it)) }

            mainExecutor.execute {
                if (autoplayPendingFor == currentId) autoplayPendingFor = null
                if (session == null || nextItem == null || !settings.autoplay) return@execute
                val stillLast = player.currentMediaItem?.mediaId == current.mediaId &&
                    player.currentMediaItemIndex == player.mediaItemCount - 1
                if (!stillLast) return@execute
                player.addMediaItem(nextItem)
                // Nếu video hiện tại đã kết thúc trong lúc đang tìm thì phát tiếp luôn
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            }
        }
    }

    private fun autoplayItem(video: VideoItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(video.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channel)
                    .setArtworkUri(video.thumbnailUrl?.toUri())
                    .setExtras(Bundle().apply { putBoolean(LumiKeys.AUTOPLAY, true) })
                    .build()
            )
            .build()

    private fun onAutoplaySettingChanged() {
        if (settings.autoplay) {
            maybeQueueRelated()
        } else {
            // Tắt tự phát → bỏ các video tự phát còn chờ phía sau
            for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
                if (player.getMediaItemAt(i).isAutoplay) player.removeMediaItem(i)
            }
        }
    }

    private fun PlaybackException.isEmbedOnly(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("UNPLAYABLE") || message.contains("not available", ignoreCase = true)) return true
            cause = cause.cause
        }
        return false
    }

    private fun PlaybackException.isHttpForbidden(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val MAX_ERROR_SKIPS = 3
        const val MAX_HISTORY = 200
        const val MAX_RELATED_SHOWN = 20
    }
}
