package dev.lumi.player.history

import android.content.Context
import android.util.Log
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.extractor.VideoItemCodec
import dev.lumi.player.extractor.VideoRepository.videoIdOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class HistoryEntry(
    val video: VideoItem,
    val watchedAt: Long,
    /** Video liên quan lúc xem — dùng để dựng mục "Dành cho bạn" mà không cần đăng nhập. */
    val related: List<VideoItem>,
)

/**
 * Lịch sử xem, lưu thành file JSON trong bộ nhớ riêng của app (không gửi đi đâu).
 * Service ghi khi bắt đầu phát một video; giao diện đọc qua [entries].
 */
object WatchHistory {

    private const val MAX_ENTRIES = 300
    private const val MAX_RELATED = 15

    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    private val loaded = CompletableDeferred<Unit>()

    fun init(context: Context) {
        file = File(context.filesDir, "watch_history.json")
        io.execute {
            val fromDisk = load()
            synchronized(this) {
                // Giữ những gì đã ghi trong lúc đang đọc file
                val ids = _entries.value.mapTo(HashSet()) { videoIdOf(it.video.url) }
                _entries.value = _entries.value + fromDisk.filter { videoIdOf(it.video.url) !in ids }
            }
            loaded.complete(Unit)
        }
    }

    /** Đợi đọc xong file lịch sử (lần đầu mở app). */
    suspend fun awaitLoaded() = loaded.await()

    @Synchronized
    fun record(video: VideoItem, related: List<VideoItem>) {
        val id = videoIdOf(video.url)
        val entry = HistoryEntry(video, System.currentTimeMillis(), related.take(MAX_RELATED))
        _entries.value = (listOf(entry) + _entries.value.filter { videoIdOf(it.video.url) != id })
            .take(MAX_ENTRIES)
        save()
    }

    @Synchronized
    fun remove(url: String) {
        val id = videoIdOf(url)
        _entries.value = _entries.value.filter { videoIdOf(it.video.url) != id }
        save()
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save()
    }

    /**
     * Đề xuất "Dành cho bạn": trộn xen kẽ video liên quan của vài video xem gần nhất,
     * bỏ những video đã xem.
     */
    fun recommendations(limit: Int = 60): List<VideoItem> {
        val all = _entries.value
        val watched = all.mapTo(HashSet()) { videoIdOf(it.video.url) }
        val lists = all.take(8).map { it.related }
        val result = LinkedHashMap<String, VideoItem>()
        var round = 0
        while (result.size < limit && lists.any { round < it.size }) {
            for (list in lists) {
                val v = list.getOrNull(round) ?: continue
                val id = videoIdOf(v.url)
                if (id !in watched && !v.isLive) result.putIfAbsent(id, v)
            }
            round++
        }
        return result.values.toList()
    }

    private fun save() {
        val snapshot = _entries.value
        io.execute {
            try {
                val array = JSONArray()
                snapshot.forEach { e ->
                    array.put(JSONObject().apply {
                        put("video", VideoItemCodec.toJson(e.video))
                        put("at", e.watchedAt)
                        put("related", JSONArray().apply { e.related.forEach { put(VideoItemCodec.toJson(it)) } })
                    })
                }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(array.toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.w("WatchHistory", "Không lưu được lịch sử", e)
            }
        }
    }

    private fun load(): List<HistoryEntry> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val video = VideoItemCodec.fromJson(o.getJSONObject("video")) ?: return@mapNotNull null
                val related = o.optJSONArray("related")?.let { r ->
                    (0 until r.length()).mapNotNull { VideoItemCodec.fromJson(r.getJSONObject(it)) }
                }.orEmpty()
                HistoryEntry(video, o.optLong("at"), related)
            }
        }
    } catch (e: Exception) {
        Log.w("WatchHistory", "File lịch sử hỏng, bắt đầu lại", e)
        emptyList()
    }
}
