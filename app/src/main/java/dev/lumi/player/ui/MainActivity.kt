package dev.lumi.player.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.lumi.player.extractor.ChannelRepository.channelIdOf
import dev.lumi.player.extractor.ChannelSummary
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.history.WatchHistory
import dev.lumi.player.library.SubscriptionStore
import dev.lumi.player.player.EmbedPlayback
import dev.lumi.player.player.LumiCommands
import dev.lumi.player.extractor.VideoRepository
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.lumi.player.player.LumiSettings
import dev.lumi.player.player.PlaybackService
import dev.lumi.player.player.queueInsertIndex
import dev.lumi.player.updates.AppRelease
import dev.lumi.player.updates.AppUpdates
import dev.lumi.player.updates.DownloadState
import dev.lumi.player.updates.ExtractorUpdates
import dev.lumi.player.updates.ReleaseInfo
import kotlinx.coroutines.launch

/** Các màn phủ lên trên tab. Mở chồng lên nhau được (vd tìm kiếm → kênh → kênh khác). */
private sealed interface Overlay {
    data object Search : Overlay
    data object Settings : Overlay
    data class Channel(val channel: ChannelSummary) : Overlay
}

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val searchViewModel: SearchViewModel by viewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by viewModels()
    private lateinit var settings: LumiSettings

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private var tab by mutableStateOf(Tab.Home)
    private val overlays = mutableStateListOf<Overlay>()
    private var watchOpen by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private var audioOnly by mutableStateOf(false)
    private var autoplay by mutableStateOf(true)
    private var defaultQuality by mutableStateOf(720)
    private var checkingUpdate by mutableStateOf(false)
    private var appBannerDismissed by mutableStateOf(false)
    private var embedSlot by mutableStateOf<Rect?>(null)
    private var inForeground = false
    private var pendingSharedUrl: String? = null

    private val videoActions = VideoActions(
        play = { playNow(it.toMediaItem()) },
        playNext = { enqueue(it, next = true) },
        addToQueue = { enqueue(it, next = false) },
        openChannel = ::openChannel,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = LumiSettings(this)
        autoplay = settings.autoplay
        defaultQuality = settings.defaultQuality
        savedInstanceState?.let {
            tab = Tab.entries[it.getInt(KEY_TAB)]
            watchOpen = it.getBoolean(KEY_WATCH_OPEN)
            audioOnly = it.getBoolean(KEY_AUDIO_ONLY)
        }
        handleShareIntent(intent)
        connectToService()
        if (ExtractorUpdates.isStale) checkForUpdate(manual = false)

        setContent {
            LumiTheme {
                val snapshot = rememberPlayerSnapshot(controller)
                val history by WatchHistory.entries.collectAsState()
                val subscriptions by SubscriptionStore.channels.collectAsState()
                val updateBanner by ExtractorUpdates.banner.collectAsState()
                val latestRelease by ExtractorUpdates.latest.collectAsState()
                val appRelease by AppUpdates.latest.collectAsState()
                val downloadState by AppUpdates.download.collectAsState()
                val banner = homeBanner(appRelease, downloadState, updateBanner)
                // Video YouTube không cho lấy luồng (vd «dành cho trẻ em») → trình phát nhúng chính thức
                val embedVideoId = snapshot.mediaId
                    ?.takeIf { snapshot.errorOpenExternally }
                    ?.let(VideoRepository::videoIdOf)
                val embedState by EmbedPlayback.state.collectAsState()
                val playerProgress = rememberPlaybackProgress(controller)
                val dark = isSystemInDarkTheme()
                val subscribedIds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { channelIdOf(it.url) } }
                val isSubscribed: (String?) -> Boolean = { url -> url != null && channelIdOf(url) in subscribedIds }

                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { subscriptionsViewModel.importTakeout(contentResolver, it) }
                }
                val startImport = { importLauncher.launch(arrayOf("*/*")) }

                // Android 13+: xin quyền thông báo 1 lần (để báo khi có bản NewPipeExtractor mới)
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED &&
                        !settings.prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
                    ) {
                        settings.prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val importMessage = subscriptionsViewModel.message
                LaunchedEffect(importMessage) {
                    importMessage?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        subscriptionsViewModel.consumeMessage()
                    }
                }
                LaunchedEffect(tab, overlays.size) {
                    if (tab == Tab.Subscriptions && overlays.isEmpty()) subscriptionsViewModel.refreshIfStale()
                }

                // Trang xem video có nền đen phía trên → chữ thanh trạng thái phải là màu sáng
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark && !watchOpen
                }

                BackHandler(enabled = watchOpen || overlays.isNotEmpty() || tab != Tab.Home) {
                    when {
                        fullscreen -> changeFullscreen(false)
                        watchOpen -> watchOpen = false
                        overlays.isNotEmpty() -> overlays.removeAt(overlays.lastIndex)
                        else -> tab = Tab.Home
                    }
                }

                val overlay = overlays.lastOrNull()
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0),
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            if (overlay == null) {
                                LumiTopBar(
                                    onSearch = { overlays += Overlay.Search },
                                    onSettings = { overlays += Overlay.Settings },
                                )
                            }
                        },
                        bottomBar = {
                            Column {
                                if (snapshot.hasMedia) {
                                    MiniPlayer(
                                        snapshot = if (embedVideoId != null) {
                                            snapshot.copy(isPlaying = embedState.playing, isLoading = false)
                                        } else {
                                            snapshot
                                        },
                                        progress = if (embedVideoId != null) embedState.progress else playerProgress,
                                        onOpen = { watchOpen = true },
                                        onPlayPause = {
                                            if (embedVideoId != null) EmbedPlayback.toggle()
                                            else controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                        },
                                        onClose = {
                                            controller?.stop()
                                            controller?.clearMediaItems()
                                        },
                                    )
                                }
                                LumiBottomBar(
                                    selected = tab.takeIf { overlay == null },
                                    onSelect = {
                                        overlays.clear()
                                        tab = it
                                    },
                                )
                            }
                        },
                    ) { padding ->
                        when (overlay) {
                            Overlay.Search -> SearchScreen(
                                state = searchViewModel.state,
                                actions = videoActions,
                                onQueryChange = searchViewModel::onQueryChange,
                                onSearch = searchViewModel::search,
                                onStartEditing = searchViewModel::startEditing,
                                onLoadMore = searchViewModel::loadMore,
                                isSubscribed = { isSubscribed(it) },
                                onToggleSubscribe = ::toggleSubscribe,
                                onBack = { overlays.removeAt(overlays.lastIndex) },
                                contentPadding = padding,
                            )
                            Overlay.Settings -> SettingsScreen(
                                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                                defaultQuality = defaultQuality,
                                onDefaultQualityChange = {
                                    defaultQuality = it
                                    settings.defaultQuality = it
                                },
                                autoplay = autoplay,
                                onAutoplayChange = ::changeAutoplay,
                                subscriptionCount = subscriptions.size,
                                importing = subscriptionsViewModel.state.importing,
                                onImport = startImport,
                                latestRelease = latestRelease,
                                appRelease = appRelease,
                                downloadState = downloadState,
                                onUpdateApp = ::updateApp,
                                checkingUpdate = checkingUpdate,
                                onCheckUpdate = { checkForUpdate(manual = true) },
                                onOpenRelease = ::openUrl,
                                onBack = { overlays.removeAt(overlays.lastIndex) },
                            )
                            is Overlay.Channel -> {
                                val channelViewModel: ChannelViewModel =
                                    viewModel(key = overlay.channel.url) { ChannelViewModel(overlay.channel) }
                                ChannelScreen(
                                    state = channelViewModel.state,
                                    subscribed = isSubscribed(overlay.channel.url),
                                    onToggleSubscribe = ::toggleSubscribe,
                                    actions = videoActions,
                                    onLoadMore = channelViewModel::loadMore,
                                    onRetry = channelViewModel::load,
                                    onBack = { overlays.removeAt(overlays.lastIndex) },
                                    contentPadding = padding,
                                )
                            }
                            null -> when (tab) {
                                Tab.Home -> HomeScreen(
                                    state = homeViewModel.state,
                                    actions = videoActions,
                                    onSelectChip = homeViewModel::select,
                                    onLoadMore = homeViewModel::loadMore,
                                    onRefresh = homeViewModel::refresh,
                                    banner = banner,
                                    contentPadding = padding,
                                )
                                Tab.Subscriptions -> SubscriptionsScreen(
                                    state = subscriptionsViewModel.state,
                                    actions = videoActions,
                                    onImport = startImport,
                                    onRefresh = subscriptionsViewModel::refresh,
                                    contentPadding = padding,
                                )
                                Tab.You -> YouScreen(
                                    history = history,
                                    actions = videoActions,
                                    onRemove = WatchHistory::remove,
                                    onClearHistory = WatchHistory::clear,
                                    onOpenSettings = { overlays += Overlay.Settings },
                                    contentPadding = padding,
                                )
                            }
                        }
                    }

                    if (watchOpen && snapshot.hasMedia) {
                        WatchScreen(
                            player = controller,
                            snapshot = snapshot,
                            actions = videoActions,
                            audioOnly = audioOnly,
                            onAudioOnlyChange = {
                                audioOnly = it
                                applyVideoTrackState()
                            },
                            autoplay = autoplay,
                            onAutoplayChange = ::changeAutoplay,
                            onQualitySelect = ::changeQuality,
                            onQueueItemClick = { index ->
                                controller?.run {
                                    seekTo(index, 0)
                                    prepare()
                                    play()
                                }
                            },
                            onQueueItemRemove = { index -> controller?.removeMediaItem(index) },
                            onShare = { snapshot.mediaId?.let(::shareVideo) },
                            subscribed = isSubscribed(snapshot.channelUrl),
                            onToggleSubscribe = ::toggleSubscribe,
                            onOpenExternally = { snapshot.mediaId?.let(::openUrl) },
                            fullscreen = fullscreen,
                            onFullscreenChange = ::changeFullscreen,
                            onRetry = ::retry,
                            onMinimize = { watchOpen = false },
                            embedActive = embedVideoId != null,
                            onEmbedSlot = { embedSlot = it },
                        )
                    }

                    // Trình phát nhúng ở tầng ngoài cùng: phủ lên ô video khi mở màn xem,
                    // thu về 1 điểm ảnh khi thu nhỏ (vẫn phát tiếp, điều khiển qua mini-player).
                    if (embedVideoId != null) {
                        val density = LocalDensity.current
                        val slot = embedSlot.takeIf { watchOpen }
                        EmbedPlayer(
                            videoId = embedVideoId,
                            title = snapshot.title,
                            modifier = if (slot != null) {
                                with(density) {
                                    Modifier
                                        .offset { IntOffset(slot.left.roundToInt(), slot.top.roundToInt()) }
                                        .size(slot.width.toDp(), slot.height.toDp())
                                }
                            } else {
                                Modifier.size(1.dp)
                            },
                            onEnded = {
                                controller?.run {
                                    if (hasNextMediaItem()) {
                                        seekToNextMediaItem()
                                        prepare()
                                        play()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** Kết nối tới [PlaybackService]. Service sẽ được tạo nếu chưa chạy. */
    private fun connectToService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            applyVideoTrackState()
            pendingSharedUrl?.let {
                pendingSharedUrl = null
                playNow(urlMediaItem(it))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Người dùng "Chia sẻ" link video từ app khác sang Lumi. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return
        if (controller == null) pendingSharedUrl = url else playNow(urlMediaItem(url))
    }

    // ---------------------------------------------------------------- Kênh

    private fun openChannel(channel: ChannelSummary) {
        watchOpen = false
        val top = overlays.lastOrNull()
        if (top is Overlay.Channel && channelIdOf(top.channel.url) == channelIdOf(channel.url)) return
        overlays += Overlay.Channel(channel)
    }

    private fun toggleSubscribe(channel: ChannelSummary) {
        if (SubscriptionStore.isSubscribed(channel.url)) {
            SubscriptionStore.unsubscribe(channel.url)
            Toast.makeText(this, "Đã bỏ theo dõi ${channel.name}", Toast.LENGTH_SHORT).show()
        } else {
            SubscriptionStore.subscribe(channel)
            Toast.makeText(this, "Đã theo dõi ${channel.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Cập nhật thư viện

    /** Dải báo ở Trang chủ: ưu tiên "có bản Lumi mới" (đã build sẵn), rồi mới tới "có bản thư viện mới". */
    private fun homeBanner(app: AppRelease?, download: DownloadState, extractor: ReleaseInfo?): BannerInfo? = when {
        AppUpdates.hasUpdate(app) && !appBannerDismissed -> BannerInfo(
            title = "Lumi có bản mới",
            message = "Build ${app!!.versionCode}" +
                (app.extractorVersion?.let { " · NewPipeExtractor $it" } ?: "") +
                ". Bấm Cập nhật để tải và cài đặt.",
            actionLabel = when (download) {
                is DownloadState.Downloading -> "Đang tải ${(download.progress * 100).toInt()}%"
                is DownloadState.Ready -> "Cài đặt"
                else -> "Cập nhật"
            },
            onAction = ::updateApp,
            onDismiss = { appBannerDismissed = true },
            progress = (download as? DownloadState.Downloading)?.progress,
        )
        extractor != null -> BannerInfo(
            title = "Có NewPipeExtractor ${extractor.tag}",
            message = "Bạn đang dùng ${ExtractorUpdates.CURRENT}. " + if (AppUpdates.enabled) {
                "GitHub sẽ tự build bản Lumi mới trong vòng 1 ngày, Lumi sẽ báo để bạn cập nhật."
            } else {
                "YouTube có thể vừa thay đổi — cập nhật để Lumi tiếp tục phát ổn định."
            },
            actionLabel = "Xem bản mới",
            onAction = { openUrl(extractor.url) },
            onDismiss = { ExtractorUpdates.dismiss(extractor.tag) },
        )
        else -> null
    }

    /** Tải APK bản mới (nếu chưa tải) rồi mở trình cài đặt của Android. */
    private fun updateApp() {
        val release = AppUpdates.latest.value ?: return
        when (val state = AppUpdates.download.value) {
            is DownloadState.Downloading -> Unit
            is DownloadState.Ready -> installApk(state.file)
            else -> lifecycleScope.launch {
                try {
                    installApk(AppUpdates.downloadApk(this@MainActivity, release))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Không tải được bản cập nhật: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(apk: java.io.File) {
        if (!AppUpdates.canInstall(this)) {
            // Android yêu cầu cho phép 1 lần: "Cài ứng dụng không rõ nguồn gốc" cho Lumi
            Toast.makeText(this, "Bật «Cho phép từ nguồn này» cho Lumi, rồi quay lại bấm Cập nhật lần nữa.", Toast.LENGTH_LONG).show()
            AppUpdates.openInstallPermissionSettings(this)
            return
        }
        AppUpdates.install(this, apk)
    }

    private fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        lifecycleScope.launch {
            try {
                val app = runCatching { AppUpdates.check() }.getOrNull()
                val release = ExtractorUpdates.check()
                if (manual) {
                    val text = when {
                        AppUpdates.hasUpdate(app) -> "Lumi có bản mới: build ${app!!.versionCode}"
                        ExtractorUpdates.hasUpdate(release) -> "Có NewPipeExtractor mới: ${release.tag}"
                        else -> "Bạn đang dùng bản mới nhất"
                    }
                    Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) {
                    Toast.makeText(this@MainActivity, "Không kiểm tra được: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                checkingUpdate = false
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Không có app nào mở được link này", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Phát & hàng chờ

    /**
     * Phát ngay: chèn video ngay sau video hiện tại rồi chuyển sang nó.
     * Hàng chờ phía sau vẫn được giữ nguyên.
     */
    private fun playNow(item: MediaItem) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) {
            player.setMediaItem(item)
        } else {
            val index = player.currentMediaItemIndex + 1
            player.addMediaItem(index, item)
            player.seekTo(index, 0)
        }
        player.prepare()
        player.play()
        watchOpen = true
    }

    /**
     * Thêm vào hàng chờ. "Phát tiếp theo" chèn ngay sau video hiện tại;
     * "Thêm vào hàng chờ" chèn sau các video đã thêm, trước các video tự phát.
     */
    private fun enqueue(video: VideoItem, next: Boolean) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) {
            playNow(video.toMediaItem())
            return
        }
        val index = if (next) player.currentMediaItemIndex + 1 else player.queueInsertIndex()
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(index, video.toMediaItem())
        if (wasEnded) {
            player.seekTo(index, 0)
            player.play()
        }
        val message = if (next) "Sẽ phát tiếp theo" else "Đã thêm vào hàng chờ"
        Toast.makeText(this, "$message: ${video.title}", Toast.LENGTH_SHORT).show()
    }

    /** Lấy lại link luồng cho video hiện tại (link hỏng / hết hạn) mà không đụng tới hàng chờ. */
    private fun retry() {
        val player = controller ?: return
        val item = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val fresh = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setMediaMetadata(item.mediaMetadata)
            .build()
        player.replaceMediaItem(index, fresh) // service sẽ lấy lại link trong onAddMediaItems
        player.seekTo(index, position)
        player.prepare()
        player.play()
    }

    private fun changeQuality(optionIndex: Int) {
        val player = controller ?: return
        val args = Bundle().apply { putInt(LumiCommands.ARG_INDEX, optionIndex) }
        player.sendCustomCommand(LumiCommands.setQuality, args)
    }

    private fun changeAutoplay(enabled: Boolean) {
        autoplay = enabled
        settings.autoplay = enabled // service tự nghe thay đổi này
    }

    private fun shareVideo(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, "Chia sẻ video"))
    }

    // ---------------------------------------------------------------- Vòng đời & màn hình

    override fun onStart() {
        super.onStart()
        inForeground = true
        applyVideoTrackState()
    }

    override fun onStop() {
        // Tắt màn hình / chuyển app: ngừng tải hình, chỉ giữ âm thanh
        inForeground = false
        applyVideoTrackState()
        super.onStop()
    }

    /**
     * Tắt/bật luồng video. Khi tắt, ExoPlayer không tải phần hình nữa
     * → chỉ tốn băng thông cho âm thanh (~130 kbps thay vì vài Mbps).
     */
    private fun applyVideoTrackState() {
        val player = controller ?: return
        val disableVideo = audioOnly || !inForeground
        val params = player.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disableVideo) return
        player.trackSelectionParameters = params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
            .build()
    }

    private fun changeFullscreen(enabled: Boolean) {
        fullscreen = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab.ordinal)
        outState.putBoolean(KEY_WATCH_OPEN, watchOpen)
        outState.putBoolean(KEY_AUDIO_ONLY, audioOnly)
    }

    override fun onDestroy() {
        // Chỉ ngắt kết nối giao diện; service vẫn phát tiếp nếu đang phát
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val KEY_WATCH_OPEN = "watch_open"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        val URL_REGEX = Regex("""https?://\S+""")
    }
}

/** UI chỉ gửi link + tiêu đề; service sẽ tự lấy luồng phát. */
private fun VideoItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(channel)
                .setArtworkUri(thumbnailUrl?.toUri())
                .build()
        )
        .build()

private fun urlMediaItem(url: String): MediaItem = MediaItem.Builder().setMediaId(url).build()
