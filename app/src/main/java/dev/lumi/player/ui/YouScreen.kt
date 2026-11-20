package dev.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lumi.player.R
import dev.lumi.player.history.HistoryEntry

/** Tab "Bạn": lịch sử xem + lối vào cài đặt. Không cần tài khoản — tất cả lưu trên máy. */
@Composable
fun YouScreen(
    history: List<HistoryEntry>,
    actions: VideoActions,
    onRemove: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(contentPadding)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Bạn", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Không cần đăng nhập · dữ liệu chỉ lưu trên máy này",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            MenuRow(R.drawable.ic_settings, "Cài đặt", onOpenSettings)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        item {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Lịch sử",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Xoá tất cả") }
            }
        }
        if (history.isEmpty()) {
            item { CenterMessage("Chưa có gì", "Video bạn xem sẽ hiện ở đây.") }
        }
        items(history, key = { it.video.url }) { entry ->
            VideoRow(
                video = entry.video,
                actions = actions,
                trailing = {
                    IconButton(onClick = { onRemove(entry.video.url) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Xoá khỏi lịch sử", modifier = Modifier.size(20.dp))
                    }
                },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Xoá lịch sử xem?") },
            text = { Text("Mục \"Dành cho bạn\" ở Trang chủ cũng sẽ bắt đầu lại từ đầu.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearHistory()
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null)
        Spacer(Modifier.width(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
