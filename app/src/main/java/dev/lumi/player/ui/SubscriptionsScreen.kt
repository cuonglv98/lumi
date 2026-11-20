package dev.lumi.player.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumi.player.R
import dev.lumi.player.extractor.ChannelRepository
import dev.lumi.player.extractor.ChannelSummary
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.library.SubscriptionStore
import dev.lumi.player.library.TakeoutImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class SubscriptionsUiState(
    val channels: List<ChannelSummary> = emptyList(),
    val items: List<VideoItem> = emptyList(),
    val loading: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val importing: Boolean = false,
)

/** Tab "Kênh đăng ký": gom video mới nhất của mọi kênh đang theo dõi, xếp theo thời gian. */
class SubscriptionsViewModel : ViewModel() {

    var state by mutableStateOf(SubscriptionsUiState())
        private set

    /** Thông báo 1 lần (kết quả nhập Takeout) — giao diện hiện Toast rồi xoá. */
    var message by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null
    private var loadedKey: Set<String> = emptySet()
    private var loadedAt = 0L

    init {
        viewModelScope.launch {
            SubscriptionStore.channels.collect {
                // Ảnh kênh có thể về sau khi feed đã tải (kênh vừa nhập từ Takeout) → điền lại
                state = state.copy(channels = it, items = withChannelAvatars(state.items, it))
            }
        }
    }

    fun consumeMessage() {
        message = null
    }

    /** Gọi khi mở tab: chỉ tải lại nếu danh sách kênh đổi hoặc đã quá 15 phút. */
    fun refreshIfStale() {
        val key = SubscriptionStore.channels.value.mapTo(HashSet()) { ChannelRepository.channelIdOf(it.url) }
        if (key != loadedKey || System.currentTimeMillis() - loadedAt > 15 * 60_000) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        val channels = SubscriptionStore.channels.value
        loadedKey = channels.mapTo(HashSet()) { ChannelRepository.channelIdOf(it.url) }
        if (channels.isEmpty()) {
            state = state.copy(items = emptyList(), loading = false, done = 0, total = 0, failed = 0)
            return
        }
        state = state.copy(loading = true, done = 0, total = channels.size, failed = 0)
        loadJob = viewModelScope.launch {
            val collected = mutableListOf<VideoItem>()
            val limit = Semaphore(6) // tải song song tối đa 6 kênh để không bị YouTube chặn
            coroutineScope {
                channels.forEach { channel ->
                    launch {
                        val result = withContext(Dispatchers.IO) {
                            limit.withPermit { runCatching { ChannelRepository.latestVideos(channel) } }
                        }
                        // Quay về luồng chính → cập nhật dần, kênh nào xong hiện trước
                        collected += result.getOrDefault(emptyList())
                        state = state.copy(
                            done = state.done + 1,
                            failed = state.failed + if (result.isFailure) 1 else 0,
                            items = withChannelAvatars(
                                collected
                                    .distinctBy { it.url }
                                    .sortedByDescending { it.uploadedAt ?: 0 }
                                    .take(300),
                                state.channels,
                            ),
                        )
                    }
                }
            }
            loadedAt = System.currentTimeMillis()
            state = state.copy(loading = false)
        }
    }

    fun importTakeout(resolver: ContentResolver, uri: Uri) {
        state = state.copy(importing = true)
        viewModelScope.launch {
            try {
                val channels = withContext(Dispatchers.IO) { TakeoutImporter.read(resolver, uri) }
                val added = SubscriptionStore.addAll(channels)
                val skipped = channels.size - added
                message = "Đã nhập $added kênh" + if (skipped > 0) " ($skipped kênh đã có sẵn)" else ""
                refresh()
                fillMissingAvatars()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Không nhập được: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                state = state.copy(importing = false)
            }
        }
    }

    private fun withChannelAvatars(items: List<VideoItem>, channels: List<ChannelSummary>): List<VideoItem> {
        if (items.none { it.channelAvatarUrl == null }) return items
        val avatars = channels.mapNotNull { c -> c.avatarUrl?.let { ChannelRepository.channelIdOf(c.url) to it } }.toMap()
        return items.map { video ->
            if (video.channelAvatarUrl != null || video.channelUrl == null) video
            else video.copy(channelAvatarUrl = avatars[ChannelRepository.channelIdOf(video.channelUrl)])
        }
    }

    /** File Takeout không có ảnh kênh → lấy dần ảnh + tên chuẩn từ YouTube (3 kênh một lúc). */
    private fun fillMissingAvatars() {
        val missing = SubscriptionStore.channels.value.filter { it.avatarUrl == null }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val limit = Semaphore(3)
            coroutineScope {
                missing.forEach { channel ->
                    launch {
                        limit.withPermit {
                            runCatching { ChannelRepository.summary(channel.url) }
                                .onSuccess { SubscriptionStore.update(it.copy(url = channel.url)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    state: SubscriptionsUiState,
    actions: VideoActions,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
) {
    if (state.channels.isEmpty()) {
        EmptySubscriptions(state.importing, onImport, Modifier.padding(contentPadding))
        return
    }
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "channels") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.channels, key = { it.url }) { channel ->
                        Column(
                            Modifier.width(68.dp).clickable { actions.openChannel(channel) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ChannelAvatar(channel.avatarUrl, channel.name, 56.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                channel.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item(key = "import") {
                        Column(
                            Modifier.width(68.dp).clickable(onClick = onImport),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Icon(
                                    painterResource(R.drawable.ic_upload_file),
                                    contentDescription = null,
                                    modifier = Modifier.padding(16.dp).size(24.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Nhập", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (state.loading) {
                item(key = "progress") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Đang cập nhật ${state.done}/${state.total} kênh…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else if (state.failed > 0) {
                item(key = "failed") {
                    Text(
                        "${state.failed} kênh không tải được, kéo xuống để thử lại.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.loading && state.items.isEmpty()) {
                items(2) { VideoCardPlaceholder() }
            } else if (!state.loading && state.items.isEmpty()) {
                item { CenterMessage("Chưa có video mới", "Kéo xuống để tải lại.") }
            }
            items(state.items, key = { it.url }) { VideoCard(it, actions) }
        }
    }
}

@Composable
private fun EmptySubscriptions(importing: Boolean, onImport: () -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_subs), contentDescription = null, modifier = Modifier.size(56.dp))
                Text("Chưa theo dõi kênh nào", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Mở trang một kênh và bấm «Theo dõi», hoặc nhập toàn bộ kênh bạn đã đăng ký trên YouTube " +
                        "từ Google Takeout. Danh sách chỉ lưu trên máy này.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onImport, enabled = !importing) {
                    Icon(painterResource(R.drawable.ic_upload_file), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (importing) "Đang nhập…" else "Nhập từ Google Takeout")
                }
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cách lấy file từ Google Takeout", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "Mở takeout.google.com (đăng nhập tài khoản Google của bạn trên trình duyệt).",
                        "Bấm «Bỏ chọn tất cả», rồi chỉ chọn «YouTube và YouTube Music».",
                        "Bấm «Tất cả dữ liệu YouTube…», chỉ để lại mục «kênh đăng ký» (subscriptions).",
                        "Tạo bản xuất, tải file .zip về điện thoại.",
                        "Quay lại đây, bấm «Nhập từ Google Takeout» và chọn file .zip đó (hoặc file subscriptions.csv).",
                    ).forEachIndexed { index, step ->
                        Row {
                            Text("${index + 1}. ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text(step, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Lumi không cần mật khẩu Google — chỉ đọc danh sách kênh trong file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
