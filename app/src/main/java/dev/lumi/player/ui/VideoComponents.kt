package dev.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lumi.player.R
import dev.lumi.player.extractor.ChannelResult
import dev.lumi.player.extractor.ChannelSummary
import dev.lumi.player.extractor.TimeText
import dev.lumi.player.extractor.VideoItem
import java.time.Instant
import androidx.compose.material3.Surface

/** Các thao tác trên 1 video — truyền xuống mọi danh sách để dùng chung. */
class VideoActions(
    val play: (VideoItem) -> Unit,
    val playNext: (VideoItem) -> Unit,
    val addToQueue: (VideoItem) -> Unit,
    val openChannel: (ChannelSummary) -> Unit,
)

fun VideoItem.channelSummary(): ChannelSummary? =
    channelUrl?.let { ChannelSummary(it, channel, channelAvatarUrl) }

/** "x giờ trước": tính từ thời điểm đăng (luôn đúng) nếu có, không thì dùng chữ YouTube trả về. */
fun VideoItem.dateText(): String? =
    uploadedAt?.let { TimeText.relative(Instant.ofEpochMilli(it)) } ?: displayDate(uploadedText)

fun VideoItem.metaLine(includeChannel: Boolean = true): String = listOfNotNull(
    channel.takeIf { includeChannel && it.isNotEmpty() },
    formatViews(viewCount).ifEmpty { null },
    dateText(),
).joinToString(" · ")

/** Thẻ video lớn: ảnh tràn chiều ngang, bên dưới là ảnh kênh + tiêu đề + thông tin. */
@Composable
fun VideoCard(video: VideoItem, actions: VideoActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable { actions.play(video) }) {
        Thumbnail(video, Modifier.fillMaxWidth(), corner = 0.dp)
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 20.dp)) {
            val channel = video.channelSummary()
            ChannelAvatar(
                video.channelAvatarUrl, video.channel, 36.dp,
                onClick = channel?.let { { actions.openChannel(it) } },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    video.metaLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VideoMenu(video, actions)
        }
    }
}

/** Hàng video nhỏ: ảnh bên trái, chữ bên phải — dùng cho "Tiếp theo", lịch sử. */
@Composable
fun VideoRow(
    video: VideoItem,
    actions: VideoActions?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    label: String? = null,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onClick?.invoke() ?: actions?.play?.invoke(video) }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = if (trailing == null && actions == null) 12.dp else 0.dp),
    ) {
        Thumbnail(video, Modifier.width(160.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(label, video.channel.ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (label != null && highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = video.metaLine(includeChannel = false)
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            actions != null -> VideoMenu(video, actions)
        }
    }
}

@Composable
fun Thumbnail(video: VideoItem, modifier: Modifier, corner: Dp) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val badge = if (video.isLive) "TRỰC TIẾP" else formatDuration(video.durationSeconds)
        if (badge.isNotEmpty()) {
            Text(
                badge,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        if (video.isLive) Color(0xFFCC0000) else Color.Black.copy(alpha = 0.8f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Ảnh đại diện kênh; nếu không có ảnh thì hiện chữ cái đầu. */
@Composable
fun ChannelAvatar(url: String?, name: String, size: Dp, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun VideoMenu(video: VideoItem, actions: VideoActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = "Tuỳ chọn",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Phát tiếp theo") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_queue_add), null) },
                onClick = {
                    open = false
                    actions.playNext(video)
                },
            )
            DropdownMenuItem(
                text = { Text("Thêm vào hàng chờ") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_queue_add), null) },
                onClick = {
                    open = false
                    actions.addToQueue(video)
                },
            )
            video.channelSummary()?.let { channel ->
                DropdownMenuItem(
                    text = { Text("Đi tới kênh") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_person_outline), null) },
                    onClick = {
                        open = false
                        actions.openChannel(channel)
                    },
                )
            }
        }
    }
}

/** Khung xám khi đang tải, để bố cục không bị "nhảy". */
@Composable
fun VideoCardPlaceholder() {
    val shade = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(shade))
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(shade))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shade))
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shade))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Thông báo giữa màn hình (rỗng / lỗi). */
@Composable
fun CenterMessage(title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let { (label, onClick) ->
            androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
        }
    }
}

/** Nút "Theo dõi" / "Đã theo dõi" bo tròn. */
@Composable
fun FollowButton(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (subscribed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.onSurface,
        contentColor = if (subscribed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Text(
            if (subscribed) "Đã theo dõi" else "Theo dõi",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Thẻ kênh trong kết quả tìm kiếm. */
@Composable
fun ChannelResultRow(
    result: ChannelResult,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onToggleSubscribe: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp), contentAlignment = Alignment.Center) {
            ChannelAvatar(result.summary.avatarUrl, result.summary.name, 84.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                result.summary.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                formatSubscribers(result.subscriberCount).ifEmpty { null },
                result.videoCount.takeIf { it >= 0 }?.let { "${formatCompact(it)} video" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FollowButton(subscribed, onToggleSubscribe)
        }
    }
}
