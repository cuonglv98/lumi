package dev.lumi.player.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.lumi.player.R
import dev.lumi.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Điều khiển trình phát NHÚNG của YouTube (dùng cho video «dành cho trẻ em», xem ui/EmbedPlayer.kt).
 *
 * Trình phát nhúng chạy trong WebView, không đi qua ExoPlayer/MediaSession, nên cần
 * bộ điều khiển riêng + [EmbedService] để giữ app chạy khi tắt màn hình.
 */
object EmbedPlayback {

    data class State(
        val videoId: String? = null,
        val title: String? = null,
        val playing: Boolean = false,
        val positionSeconds: Double = 0.0,
        val durationSeconds: Double = 0.0,
    ) {
        val progress: Float
            get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val stopService = Runnable { appContext?.let(EmbedService::stop) }
    private var appContext: Context? = null

    fun attach(context: Context, view: WebView, videoId: String, title: String?) {
        appContext = context.applicationContext
        main.removeCallbacks(stopService)
        webView = view
        _state.value = State(videoId = videoId, title = title)
    }

    fun updateTitle(title: String?) {
        _state.update { it.copy(title = title) }
    }

    fun detach(view: WebView) {
        if (webView !== view) return
        webView = null
        _state.value = State()
        // Chờ một chút: nếu bài tiếp theo là video thường, ExoPlayer kịp nhận vai "phát nền"
        main.postDelayed(stopService, 5_000)
    }

    /** Gọi từ JavaScript (luồng nền của WebView) mỗi 2 giây. */
    internal fun onStatus(position: Double, duration: Double, paused: Boolean) {
        val wasPlaying = _state.value.playing
        _state.update { it.copy(playing = !paused, positionSeconds = position, durationSeconds = duration) }
        if (!paused && !wasPlaying) appContext?.let(EmbedService::start)
    }

    fun toggle() = main.post { webView?.evaluateJavascript("window.lumiToggle && window.lumiToggle();", null) }
}

/**
 * Dịch vụ nền khi đang phát bằng trình phát nhúng: hiện thông báo (bắt buộc với dịch vụ nền),
 * giữ CPU + Wi-Fi thức để nhạc không bị ngắt khi tắt màn hình.
 */
class EmbedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phát video trẻ em", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(EmbedPlayback.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        scope.launch {
            EmbedPlayback.state.collect { state ->
                manager.notify(NOTIFICATION_ID, buildNotification(state))
                if (state.playing) acquireLocks() else releaseLocks()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> EmbedPlayback.toggle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumi:embed").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Lumi:embed").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun buildNotification(state: EmbedPlayback.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, EmbedService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(state.title ?: "Đang phát")
            .setContentText("Trình phát nhúng YouTube")
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setSilent(true)
            .addAction(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.playing) "Tạm dừng" else "Phát",
                toggle,
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "embed_playback"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_TOGGLE = "lumi.embed.TOGGLE"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, EmbedService::class.java))
            } catch (e: Exception) {
                // Android 12+ không cho bật dịch vụ nền khi app đang ở nền — bỏ qua, lần sau sẽ thử lại
                Log.w("EmbedService", "Không bật được dịch vụ nền", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbedService::class.java))
        }
    }
}
