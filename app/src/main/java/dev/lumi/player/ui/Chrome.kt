package dev.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lumi.player.R

/** Thanh trên cùng: logo Lumi bên trái, tìm kiếm + cài đặt bên phải. */
@Composable
fun LumiTopBar(onSearch: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(LumiBrand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Lumi", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = "Tìm kiếm") }
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Cài đặt") }
        }
    }
}

enum class Tab(val label: String, val icon: Int, val iconSelected: Int) {
    Home("Trang chủ", R.drawable.ic_home_outline, R.drawable.ic_home),
    Subscriptions("Kênh đăng ký", R.drawable.ic_subs_outline, R.drawable.ic_subs),
    You("Bạn", R.drawable.ic_person_outline, R.drawable.ic_person),
}

@Composable
fun LumiBottomBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(if (isSelected) tab.iconSelected else tab.icon), contentDescription = null) },
                label = { Text(tab.label, fontSize = 11.sp) },
            )
        }
    }
}

/** Trình phát thu nhỏ nằm trên thanh điều hướng: ảnh, tên, play/pause, đóng, thanh tiến độ. */
@Composable
fun MiniPlayer(
    snapshot: PlayerSnapshot,
    progress: Float,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = snapshot.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        snapshot.title ?: "Đang tải…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    snapshot.artist?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (snapshot.isLoading) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            painterResource(if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (snapshot.isPlaying) "Tạm dừng" else "Phát",
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Đóng trình phát")
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = LumiBrand,
                trackColor = Color.Transparent,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}
