package eu.kanade.presentation.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.data.upload.LibraryMangaProgress
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun LibraryPager(
    state: PagerState,
    contentPadding: PaddingValues,
    scrollAtStart: Boolean = false,
    hideScrollbar: Boolean = false,
    activeLetter: String? = null,
    targetMangaId: Long? = null,
    targetNonce: Int = 0,
    // 递增时通知当前分类列表回到顶部
    scrollToTopNonce: Int = 0,
    // 需要绘制高亮边框的漫画 id（手指指中或定位到的）
    hoverMangaId: Long? = null,
    bridge: LibraryIndexBarBridge? = null,
    hasActiveFilters: Boolean,
    selection: Set<Long>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getCategoryForPage: (Int) -> Category,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    // SY -->
    // 取某本漫画的下载/上传进度，只有书架「下载」分类会用到
    getMangaProgress: (LibraryItem) -> LibraryMangaProgress = { LibraryMangaProgress.EMPTY },
    // SY <--
) {
    HorizontalPager(
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalAlignment = Alignment.Top,
        // 索引条使用期间（含手指滑入列表定位时）禁止左右滑动切换分类卡片
        userScrollEnabled = activeLetter == null,
    ) { page ->
        if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
            // To make sure only one offscreen page is being composed
            return@HorizontalPager
        }
        val category = getCategoryForPage(page)
        val items = getItemsForCategory(category)

        if (items.isEmpty()) {
            LibraryPagerEmptyScreen(
                searchQuery = searchQuery,
                hasActiveFilters = hasActiveFilters,
                contentPadding = contentPadding,
                onGlobalSearchClicked = onGlobalSearchClicked,
            )
            return@HorizontalPager
        }

        val displayMode by getDisplayMode(page)
        val columns by if (displayMode != LibraryDisplayMode.List) {
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            remember(isLandscape) { getColumnsForOrientation(isLandscape) }
        } else {
            remember { mutableIntStateOf(0) }
        }

        val onClickManga: (LibraryManga) -> Unit = { onClickManga(category, it) }
        val onLongClickManga: (LibraryManga) -> Unit = { onLongClickManga(category, it) }
        val isCurrentPage = page == state.currentPage

        // SY -->
        // 只有「下载」分类才带上取值函数；其它分类传 null，item 内部完全不画进度条。
        // 注意：拿到值 ≠ 会画 —— 具体画哪几条由 `LibraryItemProgress` 决定
        // （页数条有任务才画、上传条进行中或失败才画、章节条本次任务超过 1 话才画）。
        val progressFor: ((LibraryItem) -> LibraryItemProgress?)? =
            if (category.isDownloadCategory) {
                { item -> LibraryItemProgress.of(getMangaProgress(item)) }
            } else {
                null
            }
        // SY <--

        when (displayMode) {
            LibraryDisplayMode.List -> {
                LibraryList(
                    items = items,
                    contentPadding = contentPadding,
                    scrollAtStart = scrollAtStart,
                    hideScrollbar = hideScrollbar,
                    activeLetter = activeLetter,
                    isCurrentPage = isCurrentPage,
                    targetMangaId = targetMangaId,
                    targetNonce = targetNonce,
                    scrollToTopNonce = scrollToTopNonce,
                    hoverMangaId = hoverMangaId,
                    bridge = bridge,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    // SY -->
                    progressFor = progressFor,
                    // SY <--
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                LibraryCompactGrid(
                    items = items,
                    showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                    columns = columns,
                    contentPadding = contentPadding,
                    scrollAtStart = scrollAtStart,
                    hideScrollbar = hideScrollbar,
                    activeLetter = activeLetter,
                    isCurrentPage = isCurrentPage,
                    targetMangaId = targetMangaId,
                    targetNonce = targetNonce,
                    scrollToTopNonce = scrollToTopNonce,
                    hoverMangaId = hoverMangaId,
                    bridge = bridge,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    // SY -->
                    progressFor = progressFor,
                    // SY <--
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.ComfortableGrid -> {
                LibraryComfortableGrid(
                    items = items,
                    columns = columns,
                    contentPadding = contentPadding,
                    scrollAtStart = scrollAtStart,
                    hideScrollbar = hideScrollbar,
                    activeLetter = activeLetter,
                    isCurrentPage = isCurrentPage,
                    targetMangaId = targetMangaId,
                    targetNonce = targetNonce,
                    scrollToTopNonce = scrollToTopNonce,
                    hoverMangaId = hoverMangaId,
                    bridge = bridge,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    // SY -->
                    progressFor = progressFor,
                    // SY <--
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
        }
    }
}

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
