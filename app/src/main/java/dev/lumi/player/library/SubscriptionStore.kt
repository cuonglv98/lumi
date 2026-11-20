package dev.lumi.player.library

import android.content.Context
import android.util.Log
import dev.lumi.player.extractor.ChannelRepository.channelIdOf
import dev.lumi.player.extractor.ChannelSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Danh sách kênh đang theo dõi, lưu thành file JSON trên máy (không cần tài khoản Google).
 * Kênh mới theo dõi nằm đầu danh sách.
 */
object SubscriptionStore {

    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val _channels = MutableStateFlow<List<ChannelSummary>>(emptyList())
    val channels: StateFlow<List<ChannelSummary>> = _channels

    fun init(context: Context) {
        file = File(context.filesDir, "subscriptions.json")
        io.execute {
            val fromDisk = load()
            synchronized(this) {
                val ids = _channels.value.mapTo(HashSet()) { channelIdOf(it.url) }
                _channels.value = _channels.value + fromDisk.filter { channelIdOf(it.url) !in ids }
            }
        }
    }

    fun isSubscribed(url: String?): Boolean {
        if (url == null) return false
        val id = channelIdOf(url)
        return _channels.value.any { channelIdOf(it.url) == id }
    }

    @Synchronized
    fun subscribe(channel: ChannelSummary) {
        if (isSubscribed(channel.url)) return
        _channels.value = listOf(channel) + _channels.value
        save()
    }

    @Synchronized
    fun unsubscribe(url: String) {
        val id = channelIdOf(url)
        _channels.value = _channels.value.filter { channelIdOf(it.url) != id }
        save()
    }

    /** Thêm nhiều kênh (nhập từ Takeout). Trả về số kênh mới thực sự được thêm. */
    @Synchronized
    fun addAll(channels: List<ChannelSummary>): Int {
        val existing = _channels.value.mapTo(HashSet()) { channelIdOf(it.url) }
        val fresh = channels.filter { existing.add(channelIdOf(it.url)) }
        if (fresh.isNotEmpty()) {
            _channels.value = _channels.value + fresh
            save()
        }
        return fresh.size
    }

    /** Cập nhật tên/ảnh kênh khi biết thông tin mới hơn (vd sau khi mở trang kênh). */
    @Synchronized
    fun update(channel: ChannelSummary) {
        val id = channelIdOf(channel.url)
        var changed = false
        _channels.value = _channels.value.map {
            if (channelIdOf(it.url) == id && (it.name != channel.name || it.avatarUrl != channel.avatarUrl)) {
                changed = true
                it.copy(name = channel.name, avatarUrl = channel.avatarUrl ?: it.avatarUrl)
            } else {
                it
            }
        }
        if (changed) save()
    }

    private fun save() {
        val snapshot = _channels.value
        io.execute {
            try {
                val array = JSONArray()
                snapshot.forEach {
                    array.put(JSONObject().put("url", it.url).put("name", it.name).putOpt("avatar", it.avatarUrl))
                }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(array.toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.w("SubscriptionStore", "Không lưu được danh sách kênh", e)
            }
        }
    }

    private fun load(): List<ChannelSummary> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val url = o.optString("url").ifEmpty { return@mapNotNull null }
                ChannelSummary(url, o.optString("name"), o.optString("avatar").ifEmpty { null })
            }
        }
    } catch (e: Exception) {
        Log.w("SubscriptionStore", "File kênh đăng ký hỏng", e)
        emptyList()
    }
}
