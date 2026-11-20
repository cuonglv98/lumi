package dev.lumi.player.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.lumi.player.BuildConfig
import dev.lumi.player.R
import dev.lumi.player.extractor.OkHttpDownloader
import dev.lumi.player.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Một bản Lumi do GitHub Actions build (xem .github/workflows/release.yml). */
data class AppRelease(
    val versionCode: Int,
    val title: String,
    val extractorVersion: String?,
    val apkUrl: String,
    val pageUrl: String,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Tự cập nhật Lumi: hỏi mục Releases của repo GitHub, tải APK mới và mở trình cài đặt của Android.
 * Android luôn bắt người dùng xác nhận "Cài đặt" (app ngoài cửa hàng không thể tự cài lặng lẽ).
 */
object AppUpdates {

    /** "chu-repo/ten-repo" — đặt trong gradle.properties (lumi.updateRepo). Rỗng = tắt. */
    const val REPO = BuildConfig.UPDATE_REPO
    val enabled: Boolean get() = REPO.isNotEmpty()

    private const val CHANNEL_ID = "extractor_updates" // dùng chung kênh thông báo "Cập nhật"
    private const val NOTIFICATION_ID = 2002

    private lateinit var prefs: SharedPreferences
    private val _latest = MutableStateFlow<AppRelease?>(null)
    val latest: StateFlow<AppRelease?> = _latest
    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumi_app_updates", Context.MODE_PRIVATE)
        val code = prefs.getInt("code", 0)
        if (code > 0) {
            _latest.value = AppRelease(
                versionCode = code,
                title = prefs.getString("title", "").orEmpty(),
                extractorVersion = prefs.getString("extractor", null),
                apkUrl = prefs.getString("apk", "").orEmpty(),
                pageUrl = prefs.getString("page", "").orEmpty(),
            )
        }
        // Đã cập nhật xong → dọn file APK cũ
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    fun hasUpdate(release: AppRelease? = _latest.value): Boolean =
        release != null && release.versionCode > BuildConfig.VERSION_CODE

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val request = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lumi-app")
            .build()
        val body = OkHttpDownloader.instance.client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // chưa có bản phát hành nào
            if (!response.isSuccessful) throw IllegalStateException("GitHub trả về HTTP ${response.code}")
            response.body.string()
        }
        val json = JSONObject(body)
        val code = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
            ?: return@withContext null
        val release = AppRelease(
            versionCode = code,
            title = json.optString("name"),
            extractorVersion = Regex("""extractor=(\S+)""").find(json.optString("body"))?.groupValues?.get(1),
            apkUrl = apk,
            pageUrl = json.optString("html_url"),
        )
        prefs.edit()
            .putInt("code", release.versionCode)
            .putString("title", release.title)
            .putString("extractor", release.extractorVersion)
            .putString("apk", release.apkUrl)
            .putString("page", release.pageUrl)
            .apply()
        _latest.value = release
        release
    }

    /** Tải APK về bộ nhớ đệm của app, cập nhật tiến độ qua [download]. */
    suspend fun downloadApk(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        _download.value = DownloadState.Downloading(0f)
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "lumi-${release.versionCode}.apk")
            val request = okhttp3.Request.Builder().url(release.apkUrl).header("User-Agent", "Lumi-app").build()
            OkHttpDownloader.instance.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val total = response.body.contentLength()
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) _download.value = DownloadState.Downloading(done.toFloat() / total)
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
            target
        } catch (e: Exception) {
            _download.value = DownloadState.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Android 8+: người dùng phải cho phép Lumi "cài ứng dụng không rõ nguồn gốc" (1 lần). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Mở màn hình "Cài đặt bản cập nhật?" của Android. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Thông báo hệ thống khi có bản Lumi mới (mỗi bản chỉ báo 1 lần). */
    internal fun notifyIfNeeded(context: Context, release: AppRelease) {
        if (!hasUpdate(release) || prefs.getInt("notified", 0) == release.versionCode) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Có bản build ${release.versionCode}" +
            (release.extractorVersion?.let { " (NewPipeExtractor $it)" } ?: "") + ". Mở Lumi và bấm Cập nhật."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Lumi có bản mới")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putInt("notified", release.versionCode).apply()
        } catch (e: SecurityException) {
            // Chưa cấp quyền thông báo
        }
    }
}
