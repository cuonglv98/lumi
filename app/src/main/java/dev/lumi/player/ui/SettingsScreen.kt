package dev.lumi.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.lumi.player.R
import dev.lumi.player.player.LumiSettings
import dev.lumi.player.BuildConfig
import dev.lumi.player.updates.AppRelease
import dev.lumi.player.updates.AppUpdates
import dev.lumi.player.updates.DownloadState
import dev.lumi.player.updates.ExtractorUpdates
import androidx.compose.material3.Button
import dev.lumi.player.updates.ReleaseInfo
import androidx.compose.material3.TextButton

@Composable
fun SettingsScreen(
    defaultQuality: Int,
    onDefaultQualityChange: (Int) -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    subscriptionCount: Int,
    importing: Boolean,
    onImport: () -> Unit,
    latestRelease: ReleaseInfo?,
    appRelease: AppRelease?,
    downloadState: DownloadState,
    onUpdateApp: () -> Unit,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Quay lại")
                }
                Text("Cài đặt", style = MaterialTheme.typography.titleMedium)
            }

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "Chất lượng mặc định",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Áp dụng cho video mở sau này. Nếu video không có mức này, app chọn mức gần nhất thấp hơn. " +
                        "Mức nào máy không giải mã được sẽ tự bị bỏ qua.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                LumiSettings.QUALITY_CHOICES.forEach { height ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDefaultQualityChange(height) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = height == defaultQuality, onClick = { onDefaultQualityChange(height) })
                        Text(qualityName(height), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    "Phát",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                SettingSwitch(
                    title = "Tự phát video tiếp theo",
                    subtitle = "Hết hàng chờ thì tự thêm một video liên quan chưa xem.",
                    checked = autoplay,
                    onCheckedChange = onAutoplayChange,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SectionTitle("Kênh đăng ký")
                ActionRow(
                    title = if (importing) "Đang nhập…" else "Nhập từ Google Takeout",
                    subtitle = "Đang theo dõi $subscriptionCount kênh. Chọn file .zip hoặc subscriptions.csv tải từ takeout.google.com.",
                    enabled = !importing,
                    onClick = onImport,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SectionTitle("Cập nhật")
                val appHasUpdate = AppUpdates.hasUpdate(appRelease)
                Text(
                    "Lumi: ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    when {
                        !AppUpdates.enabled -> "Chưa bật tự cập nhật (chưa cấu hình repo GitHub)."
                        appHasUpdate -> "Có bản mới: build ${appRelease!!.versionCode}" +
                            (appRelease.extractorVersion?.let { " (NewPipeExtractor $it)" } ?: "")
                        appRelease == null -> "Chưa kiểm tra bản mới."
                        else -> "Bạn đang dùng bản mới nhất."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (appHasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (appHasUpdate) {
                    val label = when (downloadState) {
                        is DownloadState.Downloading -> "Đang tải ${(downloadState.progress * 100).toInt()}%…"
                        is DownloadState.Ready -> "Cài đặt bản mới"
                        is DownloadState.Failed -> "Tải lại"
                        DownloadState.Idle -> "Cập nhật"
                    }
                    Button(
                        onClick = onUpdateApp,
                        enabled = downloadState !is DownloadState.Downloading,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) { Text(label) }
                }

                val hasUpdate = ExtractorUpdates.hasUpdate(latestRelease)
                Text(
                    "Thư viện YouTube (NewPipeExtractor): ${ExtractorUpdates.CURRENT}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    when {
                        latestRelease == null -> "Chưa kiểm tra bản mới."
                        hasUpdate && appHasUpdate -> "Bản ${latestRelease.tag} đã có trong bản Lumi mới ở trên."
                        hasUpdate && AppUpdates.enabled -> "Có ${latestRelease.tag}. GitHub sẽ tự build bản Lumi mới trong vòng 1 ngày."
                        hasUpdate -> "Có ${latestRelease.tag}. Sửa số phiên bản trong gradle/libs.versions.toml, build lại và cài lại app."
                        else -> "Đang dùng bản mới nhất (${latestRelease.tag})."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    "Lumi tự kiểm tra mỗi ngày và gửi thông báo khi có bản mới.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(vertical = 4.dp)) {
                    TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                        Text(if (checkingUpdate) "Đang kiểm tra…" else "Kiểm tra ngay")
                    }
                    TextButton(onClick = {
                        onOpenRelease(appRelease?.pageUrl?.takeIf { it.isNotEmpty() } ?: latestRelease?.url ?: ExtractorUpdates.RELEASES_PAGE)
                    }) {
                        Text("Trang phát hành")
                    }
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun qualityName(height: Int): String = when (height) {
    2160 -> "2160p (4K)"
    1440 -> "1440p (2K)"
    1080 -> "1080p (Full HD)"
    720 -> "720p (HD)"
    480 -> "480p"
    360 -> "360p (tiết kiệm dữ liệu)"
    else -> "${height}p"
}
