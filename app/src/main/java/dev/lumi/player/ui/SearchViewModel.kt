package dev.lumi.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumi.player.extractor.ChannelResult
import dev.lumi.player.extractor.FeedPage
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.extractor.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    /** Đang gõ → hiện gợi ý; đã bấm tìm → hiện kết quả. */
    val editing: Boolean = true,
    val suggestions: List<String> = emptyList(),
    val items: List<VideoItem> = emptyList(),
    val channels: List<ChannelResult> = emptyList(),
    /** Ghi chú khi YouTube chặn từ khoá gốc và app phải tìm bằng cách diễn đạt khác. */
    val notice: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel : ViewModel() {

    var state by mutableStateOf(SearchUiState())
        private set

    private var lastResult: FeedPage? = null
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun onQueryChange(query: String) {
        state = state.copy(query = query, editing = true)
        suggestJob?.cancel()
        if (query.isBlank()) {
            state = state.copy(suggestions = emptyList())
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250) // đợi người dùng gõ xong một chút rồi mới hỏi gợi ý
            val suggestions = try {
                VideoRepository.suggestions(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            state = state.copy(suggestions = suggestions)
        }
    }

    fun startEditing() {
        state = state.copy(editing = true)
    }

    fun search(query: String = state.query) {
        val q = query.trim()
        if (q.isEmpty()) return
        suggestJob?.cancel()
        searchJob?.cancel()
        lastResult = null
        state = state.copy(
            query = q, editing = false, loading = true, error = null,
            items = emptyList(), channels = emptyList(), notice = null, searched = true,
        )
        searchJob = viewModelScope.launch {
            try {
                val result = VideoRepository.search(q)
                lastResult = result
                state = state.copy(
                    loading = false,
                    items = result.items.distinctBy { it.url },
                    channels = result.channels.take(3),
                    notice = result.rewrittenQuery?.let {
                        "YouTube ẩn kết quả cho «$q» (cho rằng có thể không phù hợp). Đang hiện kết quả cho «$it»."
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadMore() {
        val previous = lastResult ?: return
        if (!previous.hasMore || state.loading || state.loadingMore) return
        state = state.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val result = VideoRepository.loadMore(previous)
                lastResult = result
                state = state.copy(loadingMore = false, items = (state.items + result.items).distinctBy { it.url })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loadingMore = false)
            }
        }
    }
}
