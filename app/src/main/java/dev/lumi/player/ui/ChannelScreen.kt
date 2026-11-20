package dev.lumi.player.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.lumi.player.R
import dev.lumi.player.extractor.ChannelDetails
import dev.lumi.player.extractor.ChannelRepository
import dev.lumi.player.extractor.ChannelSummary
import dev.lumi.player.extractor.ChannelVideosPage
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.library.SubscriptionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ChannelUiState(
    val summary: ChannelSummary,
    val details: ChannelDetails? = null,
    val videos: List<VideoItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

class ChannelViewModel(initial: ChannelSummary) : ViewModel() {

    var state by mutableStateOf(ChannelUiState(initial))
        private set

    private var page: ChannelVideosPage? = null

    init {
        load()
    }

    fun load() {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (details, first) = ChannelRepository.load(state.summary.url)
                page = first
                state = state.copy(
                    summary = details.summary,
                    details = details,
                    videos = first.items.distinctBy { it.url },
                    loading = false,
                )
                // Kênh đang theo dõi → cập nhật tên/ảnh mới nhất (hữu ích với kênh nhập từ Takeout)
                SubscriptionStore.update(details.summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadMore() {
        val previous = page ?: return
        if (!previous.hasMore || state.loading || state.loadingMore) return
        state = state.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val next = ChannelRepository.moreVideos(previous, state.summary)
                page = next
                state = state.copy(loadingMore = false, videos = (state.videos + next.items).distinctBy { it.url })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loadingMore = false)
            }
        }
    }
}

/** Trang kênh: ảnh bìa, ảnh đại diện, số người đăng ký, mô tả, nút Theo dõi, danh sách video. */
@Composable
fun ChannelScreen(
    state: ChannelUiState,
    subscribed: Boolean,
    onToggleSubscribe: (ChannelSummary) -> Unit,
    actions: VideoActions,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    InfiniteScroll(listState.layoutInfo.totalItemsCount, { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, onLoadMore)

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Quay lại")
            }
            Text(
                state.summary.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            state.details?.bannerUrl?.let { banner ->
                item(key = "banner") {
                    AsyncImage(
                        model = banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .aspectRatio(5f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            item(key = "header") { ChannelHeader(state, subscribed) { onToggleSubscribe(state.summary) } }
            item(key = "videos-title") {
                Text(
                    "Video",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            when {
                state.loading -> items(2) { VideoCardPlaceholder() }
                state.error != null -> item { CenterMessage("Không tải được kênh", state.error, action = "Thử lại" to onRetry) }
                state.videos.isEmpty() -> item { CenterMessage("Chưa có video", "Kênh này chưa đăng video nào.") }
                else -> items(state.videos, key = { it.url }) { VideoCard(it, actions) }
            }
            if (state.loadingMore) item { LoadingRow() }
        }
    }
}

@Composable
private fun ChannelHeader(state: ChannelUiState, subscribed: Boolean, onToggleSubscribe: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val details = state.details
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(state.summary.avatarUrl, state.summary.name, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.summary.name + if (details?.verified == true) "  ✓" else "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                details?.subscriberCount?.let { formatSubscribers(it) }?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val description = details?.description
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
            )
        }
        FollowButton(subscribed, onToggleSubscribe, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
    }
}
