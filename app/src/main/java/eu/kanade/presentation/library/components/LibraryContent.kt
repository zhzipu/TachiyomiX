package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.browse.components.IndexBarDragTarget
import eu.kanade.presentation.browse.components.IndexBarEdgeHint
import eu.kanade.presentation.browse.components.IndexBarEdgeScroller
import eu.kanade.presentation.browse.components.IndexBarLocateHint
import eu.kanade.presentation.browse.components.LetterIndexBar
import eu.kanade.presentation.browse.components.LocalIndexBarBottomBarVisible
import eu.kanade.presentation.browse.util.compareByInitial
import eu.kanade.presentation.util.onAnyTouch
import eu.kanade.tachiyomi.data.upload.LibraryMangaProgress
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

@Composable
fun LibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (Category, LibraryManga) -> Unit,
    onToggleRangeSelection: (Category, LibraryManga) -> Unit,
    onRefresh: () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    // SY -->
    // 取某本漫画的下载/上传进度（「下载」分类的进度条用）
    getMangaProgress: (LibraryItem) -> LibraryMangaProgress = { LibraryMangaProgress.EMPTY },
    /**
     * 「下载」分类里左上角要不要放「继续 / 暂停」两个按钮。
     *
     * 只是**开关**：按钮和弹窗都画在 `LibraryTab` 的顶栏里（见
     * [onDownloadControlsVisibleChange]），本组件不渲染它们。
     */
    showDownloadControls: Boolean = false,
    /**
     * 告诉调用方「当前这一页是不是『下载』分类、并且不在多选」。
     *
     * 「继续 / 暂停」按钮要画在顶栏左上角，而顶栏在另一棵组合树里，
     * 只有这里知道 `pagerState`（用户滑到了哪个分类）。所以用这个回调把结论送出去，
     * 由 `LibraryTab` 决定要不要在顶栏放那两个按钮。
     */
    onDownloadControlsVisibleChange: ((Boolean) -> Unit)? = null,
    // SY <--
) {
    // SY -->
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val indexBarPosition by libraryPreferences.indexBarPosition.collectAsState()
    val indexBarAlign = when (indexBarPosition) {
        LibraryPreferences.IndexBarPosition.LEFT -> Alignment.CenterStart
        LibraryPreferences.IndexBarPosition.RIGHT -> Alignment.CenterEnd
        LibraryPreferences.IndexBarPosition.OFF -> Alignment.CenterEnd
    }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var targetMangaId by remember { mutableStateOf<Long?>(null) }
    var targetNonce by remember { mutableStateOf(0) }
    // 再次点击当前分类标签时递增，通知当前列表回到顶部
    var scrollToTopNonce by remember { mutableStateOf(0) }
    // 手指指中/定位到的漫画，用于绘制醒目边框
    var highlightMangaId by remember { mutableStateOf<Long?>(null) }
    // 由当前可见的网格/列表写入的桥接：定位解析 + 按增量滚动
    val indexBarBridge = remember { LibraryIndexBarBridge() }
    // 手指当前所在区域（滑入列表后才有值），用于显示上下边缘遮罩
    var dragTarget by remember { mutableStateOf<IndexBarDragTarget?>(null) }
    // 「下载」分类里，顶栏左上角那两个控制按钮要不要显示（由分类页 + 是否多选决定）
    var showContinueAllButton by remember { mutableStateOf(false) }
    // 分类栏高度，用于让顶部遮罩正好覆盖分类栏
    var tabsHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val showTabs = showPageTabs && categories.isNotEmpty() &&
        (categories.size > 1 || !categories.first().isSystemCategory)
    val draggingInList = dragTarget != null && dragTarget != IndexBarDragTarget.Bar
    // 没有底部栏时（平板布局或导航栏隐藏），列表自己画"下移"区域，高度与"上移"区域一致
    val bottomBarVisible = LocalIndexBarBottomBarVisible.current
    val bottomZoneHeight = if (bottomBarVisible) {
        0.dp
    } else {
        with(density) { tabsHeightPx.toDp() }.takeIf { it > 0.dp } ?: 48.dp
    }
    // 手指停在分类栏/底部栏区域时缓慢滚动列表，并让底部导航栏显示"下移"遮罩
    IndexBarEdgeScroller(
        dragTarget = dragTarget,
        scrollBy = { delta -> indexBarBridge.scrollBy?.invoke(delta) ?: 0f },
    )
    // SY <--
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 任何操作都清除定位后的高亮边框
            .onAnyTouch { highlightMangaId = null },
    ) {
        Column(
            modifier = Modifier.padding(
                top = contentPadding.calculateTopPadding(),
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
            ),
        ) {
            val coercedCurrentPage = remember(categories, currentPage) { currentPage.coerceIn(0, categories.lastIndex) }
            // SY <--
            val pagerState = rememberPagerState(coercedCurrentPage) { categories.size }

            val scope = rememberCoroutineScope()
            var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

            if (showTabs) {
                LaunchedEffect(categories) {
                    if (categories.size <= pagerState.currentPage) {
                        pagerState.scrollToPage(categories.size - 1)
                    }
                }
                // 记录分类栏高度，供索引条拖动的顶部遮罩使用
                Box(modifier = Modifier.onSizeChanged { tabsHeightPx = it.height }) {
                    LibraryTabs(
                        categories = categories,
                        pagerState = pagerState,
                        getItemCountForCategory = getItemCountForCategory,
                        onTabItemClick = {
                            if (it == pagerState.currentPage) {
                                // 已在该分类，再次点击标签让列表回到顶部
                                scrollToTopNonce++
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(it)
                                }
                            }
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                PullRefresh(
                    refreshing = isRefreshing,
                    enabled = selection.isEmpty(),
                    onRefresh = {
                        val started = onRefresh()
                        if (!started) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                ) {
                    LibraryPager(
                        state = pagerState,
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding(),
                            // SY -->
                            // 为索引条让位，漫画内容朝索引条相反方向移动
                            start = if (indexBarPosition == LibraryPreferences.IndexBarPosition.LEFT) 5.dp else 0.dp,
                            end = if (indexBarPosition == LibraryPreferences.IndexBarPosition.RIGHT) 5.dp else 0.dp,
                            // SY <--
                        ),
                        // 索引条在右侧时滚动条移到左侧；索引条滑动期间隐藏滚动条
                        scrollAtStart = indexBarPosition == LibraryPreferences.IndexBarPosition.RIGHT,
                        hideScrollbar = activeLetter != null,
                        activeLetter = activeLetter,
                        targetMangaId = targetMangaId,
                        targetNonce = targetNonce,
                        scrollToTopNonce = scrollToTopNonce,
                        hoverMangaId = highlightMangaId,
                        bridge = indexBarBridge,
                        hasActiveFilters = hasActiveFilters,
                        selection = selection,
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                        getCategoryForPage = { page -> categories[page] },
                        getDisplayMode = getDisplayMode,
                        getColumnsForOrientation = getColumnsForOrientation,
                        // 索引条滑动期间临时按名称首字母排序，松手恢复
                        getItemsForCategory = { category ->
                            val items = getItemsForCategory(category)
                            if (activeLetter != null) {
                                items.sortedWith(
                                    compareByInitial { it.libraryManga.manga.title },
                                )
                            } else {
                                items
                            }
                        },
                        onClickManga = { category, manga ->
                            if (selection.isNotEmpty()) {
                                onToggleSelection(category, manga)
                            } else {
                                onClickManga(manga.manga.id)
                            }
                        },
                        onLongClickManga = onToggleRangeSelection,
                        onClickContinueReading = onContinueReadingClicked,
                        // SY -->
                        getMangaProgress = getMangaProgress,
                        // SY <--
                    )
                }

                // SY -->
                // 手指还在索引条上滑动时，给列表盖半透明遮罩提示下一步；滑入列表后消失
                IndexBarLocateHint(
                    visible = activeLetter != null && !draggingInList,
                    text = "手指移到这里，松开即可定位",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = contentPadding.calculateBottomPadding()),
                )
                if (indexBarPosition != LibraryPreferences.IndexBarPosition.OFF) {
                    LetterIndexBar(
                        modifier = Modifier
                            .fillMaxSize()
                            // 有底部栏时索引条让开底部栏；平板布局没有底部栏，索引条直接贴到屏幕下边缘，
                            // 底部这段就是列表自己画的"下移"区域
                            .padding(
                                bottom = if (bottomBarVisible) {
                                    contentPadding.calculateBottomPadding()
                                } else {
                                    0.dp
                                },
                            ),
                        position = indexBarAlign,
                        activeLetter = activeLetter,
                        hintText = "滑向漫画松手即可定位",
                        bottomZoneHeight = bottomZoneHeight,
                        onActiveLetterChange = { activeLetter = it },
                        onDragTargetChange = { dragTarget = it },
                        onDragMoved = { pos ->
                            highlightMangaId = pos?.let { indexBarBridge.locate?.invoke(it) }
                        },
                        onDragFinished = { pos ->
                            val id = pos?.let { indexBarBridge.locate?.invoke(it) }
                            targetMangaId = id
                            // 定位到的漫画保留高亮边框，直到有其它操作
                            highlightMangaId = id
                            targetNonce++
                            id != null
                        },
                    )
                }
                // SY <--
            }

            LaunchedEffect(pagerState.currentPage) {
                onChangeCurrentPage(pagerState.currentPage)
            }

            // SY -->
            // 「继续 / 暂停」按钮只属于「下载」分类这一页；跟着当前页走，滑到别的分类自动消失。
            // 也要避开多选状态：那时顶栏左上角是「退出多选」。
            val currentCategory = categories.getOrNull(pagerState.currentPage)
            showContinueAllButton = showDownloadControls &&
                selection.isEmpty() &&
                currentCategory?.isDownloadCategory == true
        }
        // SY <--

        // SY -->
        // 手指滑入列表期间：用外观色遮罩分类栏，居中提示"上移"
        if (draggingInList && showTabs && tabsHeightPx > 0) {
            IndexBarEdgeHint(
                text = "上移",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = contentPadding.calculateTopPadding())
                    .fillMaxWidth()
                    .height(with(density) { tabsHeightPx.toDp() }),
            )
        }
        // 没有底部栏时，由列表自己在底部画"下移"区域（高度同"上移"，贴屏幕下边缘）
        if (draggingInList && !bottomBarVisible) {
            IndexBarEdgeHint(
                text = "下移",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomZoneHeight),
            )
        }

        // 「继续 / 暂停」按钮现在画在顶栏左上角（需求 2 / 3），按钮本身和弹窗都在
        // `LibraryTab` 里 —— 因为顶栏不在本组件内，只有这里知道用户滑到了哪一页。
        // 所以这里只把「当前页是不是『下载』分类」这个结论送出去。
        //
        // 多选态下也不显示：底栏展开时是操作区，而且那时顶栏左上角是「退出多选」。
        LaunchedEffect(showContinueAllButton, onDownloadControlsVisibleChange) {
            onDownloadControlsVisibleChange?.invoke(showContinueAllButton)
        }
    }
}

/** 书架列表与索引条之间的桥接，由当前可见的列表/网格在组合时注册 */
class LibraryIndexBarBridge {
    /** 手指位置（列表视口坐标系）-> 漫画 id */
    var locate: ((Offset) -> Long?)? = null

    /** 按像素增量滚动列表，正数向下 */
    var scrollBy: (suspend (Float) -> Float)? = null
}

// SY -->
/**
 * 「继续」弹窗当前停在哪一层。
 *
 * 第一层选操作对象（下载 / 上传 / 下载和上传），选了「上传」再进第二层选范围
 *（继续之前的任务 / 全部章节）。弹窗画在 `LibraryTab` 里，这里只放这个枚举，
 * 免得 `LibraryTab` 自己再定义一份、两边状态对不上。
 */
enum class ContinueStage {
    /** 第一层：选操作对象。 */
    PICK_TARGET,

    /** 第二层：选了「上传」之后再选范围。 */
    PICK_UPLOAD_SCOPE,
}

/**
 * 「继续 / 暂停」弹窗里的一行选项。
 *
 * [enabled] 为 false 时（网络图源没配置好）整行置灰 —— 但仍然显示出来，
 * 让用户知道「有这个选项，只是现在用不了」，比整行藏掉更好解释。
 *
 * 公开是因为弹窗画在 `LibraryTab`（顶栏那一侧），不在这里。
 */
@Composable
fun ContinueAllActionRow(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * 顶栏左上角的「继续 / 暂停」两个图标按钮。
 *
 * 放在左上角而不是右下角悬浮（需求 2 / 3）：右下角原本的悬浮按钮会挡住列表，
 * 而且和索引条、多选底栏抢位置；顶栏左上角在「下载」分类之外都是空的，最合适。
 *
 * [onPause] 为 null 时只画「继续」。
 */
@Composable
fun LibraryDownloadControls(
    onContinue: () -> Unit,
    onPause: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onContinue) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(SYMR.strings.download_continue_all),
            )
        }
        if (onPause != null) {
            IconButton(onClick = onPause) {
                Icon(
                    imageVector = Icons.Outlined.Pause,
                    contentDescription = stringResource(SYMR.strings.action_pause_all),
                )
            }
        }
    }
}
// SY <--
