package dev.lumi.player.library

import android.content.ContentResolver
import android.net.Uri
import dev.lumi.player.extractor.ChannelRepository
import dev.lumi.player.extractor.ChannelSummary
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Đọc danh sách kênh đăng ký từ Google Takeout (takeout.google.com → "YouTube và YouTube Music").
 *
 * Hỗ trợ:
 * - `subscriptions.csv` (định dạng hiện tại: Channel Id, Channel Url, Channel Title)
 * - `subscriptions.json` (định dạng cũ)
 * - Nguyên file `.zip` tải từ Takeout (tự tìm file kênh đăng ký bên trong)
 *
 * Không phụ thuộc tên cột (Takeout tiếng Việt đổi tên cột/tên file): nhận diện ID kênh theo mẫu "UC" + 22 ký tự.
 */
object TakeoutImporter {

    private val CHANNEL_ID = Regex("""UC[0-9A-Za-z_-]{22}""")
    private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024

    class NoChannelsFound : Exception("Không tìm thấy kênh nào trong file này")

    fun read(resolver: ContentResolver, uri: Uri): List<ChannelSummary> {
        val stream = resolver.openInputStream(uri) ?: throw NoChannelsFound()
        val channels = BufferedInputStream(stream).use { input ->
            input.mark(4)
            val magic = ByteArray(2).also { input.read(it) }
            input.reset()
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) readZip(input) else parseText(input.readBytes().decodeToString())
        }
        if (channels.isEmpty()) throw NoChannelsFound()
        return channels.distinctBy { ChannelRepository.channelIdOf(it.url) }
    }

    /** Trong file zip: ưu tiên file có tên kiểu "subscriptions"/"đăng ký"; không có thì thử mọi .csv/.json. */
    private fun readZip(input: InputStream): List<ChannelSummary> {
        val preferred = mutableListOf<ChannelSummary>()
        val others = mutableListOf<ChannelSummary>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.lowercase()
                if (entry.isDirectory || !(name.endsWith(".csv") || name.endsWith(".json"))) continue
                if (entry.size > MAX_ENTRY_BYTES) continue
                val text = zip.readBytes().decodeToString()
                val isSubscriptionFile = listOf("subscri", "đăng ký", "dang ky", "đăng ký").any { it in name }
                (if (isSubscriptionFile) preferred else others) += parseText(text)
            }
        }
        return preferred.ifEmpty { others }
    }

    private fun parseText(text: String): List<ChannelSummary> {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (trimmed.startsWith("[")) parseJson(trimmed) else parseCsv(trimmed)
    }

    /** Định dạng cũ: [{ "snippet": { "title", "resourceId": { "channelId" }, "thumbnails": ... } }] */
    private fun parseJson(text: String): List<ChannelSummary> = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val snippet = array.optJSONObject(i)?.optJSONObject("snippet") ?: return@mapNotNull null
            val id = snippet.optJSONObject("resourceId")?.optString("channelId").orEmpty()
            if (!CHANNEL_ID.matches(id)) return@mapNotNull null
            val avatar = snippet.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url")
            ChannelSummary(ChannelRepository.urlForChannelId(id), snippet.optString("title"), avatar?.ifEmpty { null })
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Mỗi dòng: tìm ô chứa ID kênh; tên kênh là ô cuối cùng không phải link/ID. */
    private fun parseCsv(text: String): List<ChannelSummary> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cells = splitCsvLine(line)
                val id = cells.firstNotNullOfOrNull { CHANNEL_ID.find(it)?.value } ?: return@mapNotNull null
                val title = cells.lastOrNull { cell ->
                    cell.isNotBlank() && !cell.startsWith("http") && CHANNEL_ID.find(cell) == null
                }.orEmpty()
                ChannelSummary(ChannelRepository.urlForChannelId(id), title, null)
            }
            .toList()

    /** Tách 1 dòng CSV, hỗ trợ ô có dấu phẩy nằm trong ngoặc kép. */
    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }
}
