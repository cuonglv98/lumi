package dev.lumi.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: VideoActions,
    onSelectChip: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    banner: BannerInfo?,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    InfiniteScroll(listState.layoutInfo.totalItemsCount, { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, onLoadMore)
    LaunchedEffect(state.selected) { listState.scrollToItem(0) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            stickyHeader(key = "chips") {
                ChipRow(state.chips.map { it.label }, state.selected, onSelectChip)
            }
            banner?.let { info ->
                item(key = "banner") { InfoBanner(info) }
            }
            state.hint?.let { hint ->
                item(key = "hint") {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                state.loading && state.items.isEmpty() -> items(3) { VideoCardPlaceholder() }
                state.error != null -> item {
                    CenterMessage("Không tải được", state.error, action = "Thử lại" to onRefresh)
                }
                else -> items(state.items, key = { it.url }) { video -> VideoCard(video, actions) }
            }
            if (state.loadingMore) item { LoadingRow() }
        }
    }
}

/** Hàng chip chủ đề cuộn ngang, dính ở trên khi cuộn danh sách. */
@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(labels) { index, label ->
                val isSelected = index == selected
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

/** Gọi [onLoadMore] khi cuộn gần cuối danh sách. */
@Composable
fun InfiniteScroll(totalCount: Int, lastVisible: () -> Int, onLoadMore: () -> Unit) {
    val nearEnd by remember(totalCount) { derivedStateOf { totalCount > 0 && lastVisible() >= totalCount - 5 } }
    LaunchedEffect(nearEnd, totalCount) { if (nearEnd) onLoadMore() }
}

/** Nội dung dải báo ở đầu Trang chủ (cập nhật app / thư viện). */
data class BannerInfo(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    /** 0..1 khi đang tải bản cập nhật. */
    val progress: Float? = null,
)

@Composable
private fun InfoBanner(info: BannerInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(info.message, style = MaterialTheme.typography.bodySmall)
            info.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
            }
            Row(Modifier.align(Alignment.End)) {
                info.onDismiss?.let { TextButton(onClick = it) { Text("Ẩn") } }
                if (info.actionLabel != null && info.onAction != null) {
                    TextButton(onClick = info.onAction) { Text(info.actionLabel) }
                }
            }
        }
    }
}
