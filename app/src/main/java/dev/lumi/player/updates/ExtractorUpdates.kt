package dev.lumi.player.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lumi.player.BuildConfig
import dev.lumi.player.R
import dev.lumi.player.extractor.OkHttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(val tag: String, val url: String, val publishedAt: String?)

/**
 * Theo dõi phiên bản mới của NewPipeExtractor trên GitHub.
 *
 * Vì sao cần: YouTube thay đổi thường xuyên; khi đó đội NewPipe phát hành bản sửa.
 * Lumi chỉ chạy lại bình thường sau khi bạn đổi số phiên bản trong gradle/libs.versions.toml và build lại.
 */
object ExtractorUpdates {

    /** Phiên bản đang dùng — lấy từ gradle/libs.versions.toml lúc build. */
    const val CURRENT = BuildConfig.EXTRACTOR_VERSION
    const val RELEASES_PAGE = "https://github.com/TeamNewPipe/NewPipeExtractor/releases"

    private const val API = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"
    private const val WORK_NAME = "extractor-update-check"
    private const val CHANNEL_ID = "extractor_updates"
    private const val NOTIFICATION_ID = 2001
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences

    private val _latest = MutableStateFlow<ReleaseInfo?>(null)
    /** Bản mới nhất đã biết (có thể trùng bản đang dùng). */
    val latest: StateFlow<ReleaseInfo?> = _latest

    private val _banner = MutableStateFlow<ReleaseInfo?>(null)
    /** Bản mới cần báo trên Trang chủ (null = không có gì để báo). */
    val banner: StateFlow<ReleaseInfo?> = _banner

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumi_updates", Context.MODE_PRIVATE)
        _latest.value = prefs.getString("tag", null)?.let {
            ReleaseInfo(it, prefs.getString("url", RELEASES_PAGE)!!, prefs.getString("published", null))
        }
        refreshBanner()
        createChannel(context)
        schedule(context)
    }

    val lastCheckAt: Long get() = prefs.getLong("checked_at", 0)
    val isStale: Boolean get() = System.currentTimeMillis() - lastCheckAt > DAY_MS

    fun hasUpdate(release: ReleaseInfo? = _latest.value): Boolean =
        release != null && isNewer(release.tag, CURRENT)

    /** Ẩn dải báo cho bản này (bản mới hơn nữa vẫn sẽ được báo). */
    fun dismiss(tag: String) {
        prefs.edit().putString("dismissed", tag).apply()
        refreshBanner()
    }

    /** Hỏi GitHub bản mới nhất. Gọi mạng — chạy trong coroutine. */
    suspend fun check(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lumi-app")
            .build()
        val body = OkHttpDownloader.instance.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub trả về HTTP ${response.code}")
            response.body.string()
        }
        val json = JSONObject(body)
        val release = ReleaseInfo(
            tag = json.getString("tag_name"),
            url = json.optString("html_url").ifEmpty { RELEASES_PAGE },
            publishedAt = json.optString("published_at").ifEmpty { null },
        )
        prefs.edit()
            .putString("tag", release.tag)
            .putString("url", release.url)
            .putString("published", release.publishedAt)
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
        _latest.value = release
        refreshBanner()
        release
    }

    /** "v0.27.2" > "v0.27.1"? So từng phần số. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun refreshBanner() {
        val release = _latest.value
        _banner.value = release?.takeIf { hasUpdate(it) && prefs.getString("dismissed", null) != it.tag }
    }

    // ---------------------------------------------------------------- Kiểm tra nền hằng ngày

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Cập nhật thư viện YouTube", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Báo khi NewPipeExtractor có bản mới (cần để Lumi tiếp tục phát được khi YouTube thay đổi)"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Gửi thông báo hệ thống (mỗi bản mới chỉ báo 1 lần). */
    internal fun notifyIfNeeded(context: Context, release: ReleaseInfo) {
        if (!hasUpdate(release) || prefs.getString("notified", null) == release.tag) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, release.url.toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Có NewPipeExtractor ${release.tag}")
            .setContentText("Bạn đang dùng $CURRENT. Cập nhật để Lumi tiếp tục chạy ổn khi YouTube thay đổi.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Bạn đang dùng $CURRENT. Sửa số phiên bản trong gradle/libs.versions.toml thành " +
                        "${release.tag}, build lại và cài lại app."
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString("notified", release.tag).apply()
        } catch (e: SecurityException) {
            // Người dùng chưa cấp quyền thông báo
        }
    }
}

/** Chạy nền mỗi ngày 1 lần (khi có mạng) để hỏi GitHub. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var failed = false
        // Có bản Lumi mới (đã build sẵn) → báo bản app; không thì báo bản thư viện
        try {
            AppUpdates.check()?.let { AppUpdates.notifyIfNeeded(applicationContext, it) }
        } catch (e: Exception) {
            failed = true
        }
        try {
            val release = ExtractorUpdates.check()
            if (!AppUpdates.hasUpdate()) ExtractorUpdates.notifyIfNeeded(applicationContext, release)
        } catch (e: Exception) {
            failed = true
        }
        return if (failed) Result.retry() else Result.success()
    }
}
