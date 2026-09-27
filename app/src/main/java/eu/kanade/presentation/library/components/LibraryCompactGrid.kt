package eu.kanade.presentation.library.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.util.initialOfTitle
import eu.kanade.presentation.util.flash
import eu.kanade.presentation.util.itemIndexAt
import eu.kanade.presentation.util.pointerHighlight
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryItem>,
    showTitle: Boolean,
    columns: Int,
    contentPadding: PaddingValues,
    scrollAtStart: Boolean = false,
    hideScrollbar: Boolean = false,
    activeLetter: String? = null,
    isCurrentPage: Boolean = false,
    targetMangaId: Long? = null,
    targetNonce: Int = 0,
    scrollToTopNonce: Int = 0,
    hoverMangaId: Long? = null,
    bridge: LibraryIndexBarBridge? = null,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    // SY -->
    // 书架「下载」分类会传入取值函数，其它分类保持 null（不显示进度条）
    progressFor: ((LibraryItem) -> LibraryItemProgress?)? = null,
    // SY <--
    onGlobalSearchClicked: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    // 进入检索前的位置，未命中任何漫画时恢复
    var savedIndex by remember { mutableStateOf(-1) }
    var savedOffset by remember { mutableStateOf(0) }
    LaunchedEffect(activeLetter) {
        val letter = activeLetter ?: return@LaunchedEffect
        // 进入检索时先记住当前位置（只在本次检索开始时记一次）
        if (savedIndex < 0) {
            savedIndex = gridState.firstVisibleItemIndex
            savedOffset = gridState.firstVisibleItemScrollOffset
        }
        val offset = if (searchQuery.isNullOrEmpty()) 0 else 1
        val idx = items.indexOfFirst { initialOfTitle(it.libraryManga.manga.title) == letter[0] }
        if (idx >= 0) {
            gridState.scrollToItem(idx + offset)
        }
    }
    // 松手后：恢复原排序并把网格滚动到手指松开时选中的漫画；
    // 没有指向任何漫画时不做定位与闪烁，直接回到检索前的位置
    LaunchedEffect(targetNonce, activeLetter) {
        if (targetNonce == 0 || activeLetter != null) return@LaunchedEffect
        // 等待网格切回原排序并完成一次布局，避免按旧排序的索引错位
        withFrameNanos { }
        if (targetMangaId == null) {
            if (savedIndex >= 0) {
                gridState.scrollToItem(savedIndex, savedOffset)
                savedIndex = -1
            }
            return@LaunchedEffect
        }
        val headerCount = if (searchQuery.isNullOrEmpty()) 0 else 1
        val found = items.indexOfFirst { it.libraryManga.manga.id == targetMangaId }
        savedIndex = -1
        if (found >= 0) {
            gridState.scrollToItem(found + headerCount)
        }
    }
    // 再次点击当前分类标签时，带动画回到网格顶部
    LaunchedEffect(scrollToTopNonce) {
        if (scrollToTopNonce > 0 && isCurrentPage) {
            gridState.animateScrollToItem(0)
        }
    }
    // 注册当前页面的定位解析与滚动：把手指位置映射为漫画 id（基于检索排序列表）
    val currentItems = rememberUpdatedState(items)
    SideEffect {
        if (isCurrentPage) {
            bridge?.locate = { pos ->
                val gi = gridState.itemIndexAt(pos.x, pos.y)
                val headerCount = if (searchQuery.isNullOrEmpty()) 0 else 1
                gi?.let { currentItems.value.getOrNull(it - headerCount)?.libraryManga?.manga?.id }
            }
            bridge?.scrollBy = { delta -> gridState.scrollBy(delta) }
        }
    }
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        state = gridState,
        contentPadding = contentPadding,
        scrollAtStart = scrollAtStart,
        hideScrollbar = hideScrollbar,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = items,
            contentType = { "library_compact_grid_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            Box(
                modifier = Modifier
                    .flash(manga.id == targetMangaId, targetNonce)
                    .pointerHighlight(manga.id == hoverMangaId),
            ) {
                MangaCompactGridItem(
                    isSelected = manga.id in selection,
                    title = manga.title.takeIf { showTitle },
                    coverData = MangaCover(
                        mangaId = manga.id,
                        sourceId = manga.source,
                        isMangaFavorite = manga.favorite,
                        ogUrl = manga.thumbnailUrl,
                        lastModified = manga.coverLastModified,
                    ),
                    coverBadgeStart = {
                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                        UnreadBadge(count = libraryItem.badges.unreadCount)
                    },
                    coverBadgeEnd = {
                        Column(horizontalAlignment = Alignment.End) {
                            LanguageBadge(
                                isLocal = libraryItem.badges.isLocal,
                                sourceLanguage = libraryItem.badges.sourceLanguage,
                            )
                            if (!libraryItem.isLocal && libraryItem.sourceName.isNotBlank()) {
                                SourceNameBadge(sourceName = libraryItem.sourceName)
                            }
                        }
                    },
                    onLongClick = { onLongClick(libraryItem.libraryManga) },
                    onClick = { onClick(libraryItem.libraryManga) },
                    onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                        { onClickContinueReading(libraryItem.libraryManga) }
                    } else {
                        null
                    },
                    // SY -->
                    progress = progressFor?.invoke(libraryItem),
                    // SY <--
                )
            }
        }
    }
}
