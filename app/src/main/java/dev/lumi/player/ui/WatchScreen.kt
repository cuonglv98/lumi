@file:OptIn(UnstableApi::class)

package dev.lumi.player.ui

import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.lumi.player.R
import dev.lumi.player.extractor.ChannelSummary

/** Trang xem video: trình phát ở trên, thông tin + hàng chờ + "Tiếp theo" ở dưới. */
@Composable
fun WatchScreen(
    player: Player?,
    snapshot: PlayerSnapshot,
    actions: VideoActions,
    audioOnly: Boolean,
    onAudioOnlyChange: (Boolean) -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    onQualitySelect: (Int) -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    onShare: () -> Unit,
    subscribed: Boolean,
    onToggleSubscribe: (ChannelSummary) -> Unit,
    onOpenExternally: () -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onMinimize: () -> Unit,
) {
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player, fullscreen = true, snapshot.isPlaying, onFullscreenChange, Modifier.fillMaxSize())
        }
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(Color.Black).statusBarsPadding()) {
                VideoSurface(
                    player,
                    fullscreen = false,
                    snapshot.isPlaying,
                    onFullscreenChange,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                IconButton(onClick = onMinimize, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Thu nhỏ", tint = Color.White)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "info") { TitleBlock(snapshot) }
                item(key = "channel") {
                    ChannelBlock(snapshot, subscribed, onOpenChannel = actions.openChannel, onToggleSubscribe = onToggleSubscribe)
                }
                item(key = "actions") {
                    ActionRow(
                        snapshot = snapshot,
                        audioOnly = audioOnly,
                        onAudioOnlyChange = onAudioOnlyChange,
                        autoplay = autoplay,
                        onAutoplayChange = onAutoplayChange,
                        onQualitySelect = onQualitySelect,
                        onShare = onShare,
                    )
                }
                snapshot.error?.let { message ->
                    item(key = "error") {
                        ErrorCard(message, onRetry, onOpenExternally.takeIf { snapshot.errorOpenExternally })
                    }
                }
                if (snapshot.queue.size > 1) {
                    item(key = "queue-header") {
                        SectionHeader("Hàng chờ", "${snapshot.currentIndex + 1}/${snapshot.queue.size}")
                    }
                    items(snapshot.queue, key = { "q${it.index}" }) { entry ->
                        val isCurrent = entry.index == snapshot.currentIndex
                        VideoRow(
                            video = entry.asVideo(),
                            actions = null,
                            onClick = { onQueueItemClick(entry.index) },
                            label = when {
                                isCurrent -> "▶ Đang phát"
                                entry.isAutoplay -> "Tự phát"
                                else -> null
                            },
                            highlighted = isCurrent,
                            trailing = {
                                if (!isCurrent) {
                                    IconButton(onClick = { onQueueItemRemove(entry.index) }) {
                                        Icon(painterResource(R.drawable.ic_close), "Xoá khỏi hàng chờ", Modifier.size(20.dp))
                                    }
                                } else {
                                    Spacer(Modifier.width(48.dp))
                                }
                            },
                        )
                    }
                }
                if (snapshot.related.isNotEmpty()) {
                    item(key = "related-header") { SectionHeader("Tiếp theo", null) }
                    items(snapshot.related, key = { "r${it.url}" }) { video -> VideoRow(video, actions) }
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(snapshot: PlayerSnapshot) {
    var expanded by rememberSaveable(snapshot.mediaId) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = snapshot.description.isNotEmpty()) { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            snapshot.title ?: "Đang tải…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            formatViews(snapshot.viewCount).ifEmpty { null },
            displayDate(snapshot.uploadedText),
            if (snapshot.description.isNotEmpty() && !expanded) "…xem thêm" else null,
        ).joinToString("  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    snapshot.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ChannelBlock(
    snapshot: PlayerSnapshot,
    subscribed: Boolean,
    onOpenChannel: (ChannelSummary) -> Unit,
    onToggleSubscribe: (ChannelSummary) -> Unit,
) {
    val name = snapshot.artist ?: return
    val channel = snapshot.channelUrl?.let { ChannelSummary(it, name, snapshot.channelAvatarUrl) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = channel != null) { channel?.let(onOpenChannel) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(snapshot.channelAvatarUrl, name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subs = formatSubscribers(snapshot.subscriberCount)
            if (subs.isNotEmpty()) {
                Text(subs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (channel != null) {
            Spacer(Modifier.width(8.dp))
            FollowButton(subscribed, onClick = { onToggleSubscribe(channel) })
        }
    }
}

/** Hàng nút bo tròn cuộn ngang: lượt thích, chất lượng, chỉ nghe, tự phát, chia sẻ. */
@Composable
private fun ActionRow(
    snapshot: PlayerSnapshot,
    audioOnly: Boolean,
    onAudioOnlyChange: (Boolean) -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    onQualitySelect: (Int) -> Unit,
    onShare: () -> Unit,
) {
    var qualityMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (snapshot.likeCount >= 0) {
            item { Pill(R.drawable.ic_thumb_up, formatCompact(snapshot.likeCount)) }
        }
        if (snapshot.qualities.isNotEmpty()) {
            item {
                Box {
                    Pill(R.drawable.ic_hd, snapshot.qualities.getOrNull(snapshot.selectedQuality) ?: "Chất lượng") {
                        qualityMenu = true
                    }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        snapshot.qualities.forEachIndexed { index, label ->
                            val selected = index == snapshot.selectedQuality
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (selected) "✓  $label" else "     $label",
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    qualityMenu = false
                                    onQualitySelect(index)
                                },
                            )
                        }
                    }
                }
            }
        }
        item { Pill(R.drawable.ic_headphones, "Chỉ nghe", selected = audioOnly) { onAudioOnlyChange(!audioOnly) } }
        item { Pill(R.drawable.ic_autoplay, "Tự phát", selected = autoplay) { onAutoplayChange(!autoplay) } }
        item { Pill(R.drawable.ic_share, "Chia sẻ", onClick = onShare) }
    }
}

@Composable
private fun Pill(icon: Int, label: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val background = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = content,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onOpenExternally: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Không phát được", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Row {
                TextButton(onClick = onRetry) { Text("Thử lại") }
                onOpenExternally?.let { open ->
                    TextButton(onClick = open) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mở bằng app YouTube")
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(
    player: Player?,
    fullscreen: Boolean,
    keepScreenOn: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val currentOnFullscreen by rememberUpdatedState(onFullscreenChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                // Nút ⏮ ⏭ tự hiện khi hàng chờ có bài trước/sau
                artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
                setFullscreenButtonClickListener { currentOnFullscreen(it) }
            }
        },
        update = { view ->
            view.player = player
            view.setFullscreenButtonState(fullscreen)
            view.keepScreenOn = keepScreenOn // giữ màn hình sáng khi đang xem
        },
        onRelease = { view -> view.player = null },
    )
}
