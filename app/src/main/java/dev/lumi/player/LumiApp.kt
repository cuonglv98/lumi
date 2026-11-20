package dev.lumi.player

import android.app.Application
import dev.lumi.player.extractor.OkHttpDownloader
import dev.lumi.player.history.WatchHistory
import dev.lumi.player.library.SubscriptionStore
import dev.lumi.player.updates.AppUpdates
import dev.lumi.player.updates.ExtractorUpdates
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class LumiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val locale = Locale.getDefault()
        val country = locale.country.ifEmpty { "VN" }
        // Khởi tạo 1 lần: cho NewPipeExtractor biết dùng downloader nào + ngôn ngữ/quốc gia kết quả
        NewPipe.init(
            OkHttpDownloader.instance,
            Localization.fromLocale(locale),
            ContentCountry(country),
        )
        WatchHistory.init(this)
        SubscriptionStore.init(this)
        AppUpdates.init(this)
        ExtractorUpdates.init(this) // + lên lịch kiểm tra bản mới mỗi ngày
    }
}
