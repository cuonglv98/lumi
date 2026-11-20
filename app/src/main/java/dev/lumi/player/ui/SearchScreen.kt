package dev.lumi.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.lumi.player.R
import dev.lumi.player.extractor.ChannelSummary

@Composable
fun SearchScreen(
    state: SearchUiState,
    actions: VideoActions,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onStartEditing: () -> Unit,
    onLoadMore: () -> Unit,
    isSubscribed: (String) -> Boolean,
    onToggleSubscribe: (ChannelSummary) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!state.searched) focus.requestFocus() }

    fun submit(query: String) {
        keyboard?.hide()
        onSearch(query)
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Quay lại")
            }
            TextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Tìm kiếm") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Xoá")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) onStartEditing() },
            )
        }

        if (state.editing && state.query.isNotBlank()) {
            // Gợi ý từ khoá khi đang gõ
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.suggestions) { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable { submit(suggestion) }.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(suggestion, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onQueryChange("$suggestion ") }) {
                            Icon(
                                painterResource(R.drawable.ic_north_west),
                                contentDescription = "Điền từ khoá này",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            return@Column
        }

        val listState = rememberLazyListState()
        InfiniteScroll(listState.layoutInfo.totalItemsCount, { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, onLoadMore)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.error != null -> CenterMessage("Không tải được kết quả", state.error, action = "Thử lại" to { onSearch(state.query) })
            state.searched && !state.loading && state.items.isEmpty() && state.channels.isEmpty() ->
                CenterMessage("Không có kết quả", "Thử từ khoá khác nhé.")
            !state.searched -> CenterMessage(
                "Tìm video",
                "Gõ từ khoá ở trên.\nMẹo: bấm \"Chia sẻ\" một link video ở app khác rồi chọn Lumi để mở thẳng.",
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                state.notice?.let { notice ->
                    item(key = "notice") {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.channels, key = { "c" + it.summary.url }) { result ->
                    ChannelResultRow(
                        result = result,
                        subscribed = isSubscribed(result.summary.url),
                        onOpen = { actions.openChannel(result.summary) },
                        onToggleSubscribe = { onToggleSubscribe(result.summary) },
                    )
                }
                items(state.items, key = { it.url }) { VideoCard(it, actions) }
                if (state.loadingMore) item { LoadingRow() }
            }
        }
    }
}
