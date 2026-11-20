package dev.lumi.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumi.player.extractor.FeedPage
import dev.lumi.player.extractor.Kiosk
import dev.lumi.player.extractor.VideoItem
import dev.lumi.player.extractor.VideoRepository
import dev.lumi.player.history.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nguồn video của một chip ở Trang chủ. */
sealed interface FeedSource {
    val label: String

    /** Đề xuất dựa trên lịch sử xem trên máy (không cần đăng nhập). */
    data object ForYou : FeedSource {
        override val label = "Dành cho bạn"
    }

    data class Trending(val kiosk: Kiosk) : FeedSource {
        override val label get() = kiosk.label
    }

    data class Topic(override val label: String, val query: String) : FeedSource
}

data class HomeUiState(
    val chips: List<FeedSource> = listOf(FeedSource.ForYou),
    val selected: Int = 0,
    val items: List<VideoItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hint: String? = null,
)

class HomeViewModel : ViewModel() {

    var state by mutableStateOf(HomeUiState())
        private set

    private var page: FeedPage? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val kiosks = withContext(Dispatchers.IO) { VideoRepository.kiosks() }
            state = state.copy(chips = listOf(FeedSource.ForYou) + kiosks.map { FeedSource.Trending(it) } + TOPICS)
            load(0)
        }
    }

    fun select(index: Int) {
        if (index == state.selected && state.items.isNotEmpty()) return
        load(index)
    }

    fun refresh() = load(state.selected)

    private fun load(index: Int) {
        val source = state.chips.getOrNull(index) ?: return
        loadJob?.cancel()
        page = null
        state = state.copy(selected = index, items = emptyList(), loading = true, error = null, hint = null)
        loadJob = viewModelScope.launch {
            try {
                when (source) {
                    FeedSource.ForYou -> {
                        WatchHistory.awaitLoaded()
                        val recommended = WatchHistory.recommendations()
                        if (recommended.isNotEmpty()) {
                            state = state.copy(loading = false, items = recommended)
                        } else {
                            // Chưa có lịch sử → hiện mục thịnh hành đầu tiên thay thế
                            val fallback = state.chips.firstOrNull { it is FeedSource.Trending } as? FeedSource.Trending
                            val result = if (fallback != null) trending(fallback) else VideoRepository.search("thịnh hành")
                            page = result
                            state = state.copy(
                                loading = false,
                                items = result.items,
                                hint = "Xem vài video để Lumi gợi ý theo sở thích của bạn. Mọi dữ liệu chỉ lưu trên máy này.",
                            )
                        }
                    }
                    is FeedSource.Trending -> show(trending(source))
                    is FeedSource.Topic -> show(VideoRepository.search(source.query))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Mục thịnh hành của YouTube không có ở mọi quốc gia (vd bảng xếp hạng âm nhạc có thể rỗng).
     * Rỗng thì tìm kiếm theo chủ đề đó thay thế.
     */
    private suspend fun trending(source: FeedSource.Trending): FeedPage {
        val result = VideoRepository.kiosk(source.kiosk.id)
        return if (result.items.isNotEmpty()) result else VideoRepository.search("${source.label} thịnh hành")
    }

    private fun show(result: FeedPage) {
        page = result
        state = state.copy(loading = false, items = result.items.distinctBy { it.url })
    }

    fun loadMore() {
        val previous = page ?: return
        if (!previous.hasMore || state.loading || state.loadingMore) return
        state = state.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val result = VideoRepository.loadMore(previous)
                page = result
                state = state.copy(loadingMore = false, items = (state.items + result.items).distinctBy { it.url })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loadingMore = false)
            }
        }
    }

    private companion object {
        val TOPICS = listOf(
            FeedSource.Topic("Lofi", "lofi chill"),
            FeedSource.Topic("Tin tức", "tin tức hôm nay"),
            FeedSource.Topic("Hài", "hài hước"),
            FeedSource.Topic("Nấu ăn", "nấu ăn ngon"),
            FeedSource.Topic("Thể thao", "thể thao"),
            FeedSource.Topic("Học tập", "học tiếng anh"),
        )
    }
}
