package dev.lumi.player.player

import android.content.Context
import android.content.SharedPreferences

/** Cài đặt người dùng, lưu trong SharedPreferences (dùng chung cho giao diện và service). */
class LumiSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lumi_settings", Context.MODE_PRIVATE)

    /** Tự thêm video liên quan khi phát tới cuối hàng chờ. */
    var autoplay: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    /** Chiều cao video mặc định (vd 1080). Video không có mức này thì chọn mức gần nhất thấp hơn. */
    var defaultQuality: Int
        get() = prefs.getInt(KEY_DEFAULT_QUALITY, 720)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_QUALITY, value).apply()

    companion object {
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_DEFAULT_QUALITY = "default_quality"
        val QUALITY_CHOICES = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
    }
}
