package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceJumpToPageDialog
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.browse.components.SavedSearchCreateDialog
import eu.kanade.presentation.browse.components.SavedSearchDeleteDialog
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.md.follows.MangaDexFollowsScreen
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.isEhBasedSource
import exh.ui.ifSourcesLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import tachiyomi.source.network.NetworkSource
import androidx.paging.compose.collectAsLazyPagingItems as collectAsLazyPagingItemsFlow

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    // SY -->
    private val filtersJson: String? = null,
    private val savedSearch: Long? = null,
    private val smartSearchConfig: SourcesScreen.SmartSearchConfig? = null,
    // SY <--
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @OptIn(InternalComposeApi::class)
    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel {
            BrowseSourceScreenModel(
                sourceId = sourceId,
                listingQuery = listingQuery,
                // SY -->
                filtersJson = filtersJson,
                savedSearch = savedSearch,
                // SY <--
            )
        }
        val state by screenModel.state.collectAsState()

        // SY -->
        val pageSizes by screenModel.pageSizes.collectAsState()
        // SY <--

        val navigator = LocalNavigator.currentOrThrow

        // SY -->
        // Page indicator follows the currently visible items as the list scrolls.
        // It's kept separate from [BrowseSourceScreenModel.State.currentPage] so scrolling
        // doesn't rebuild the pager (which would reset the list back to the page top).
        var visiblePage by remember { mutableIntStateOf(state.currentPage) }
        val swipeOffset = remember { Animatable(0f) }
        LaunchedEffect(state.currentPage) {
            visiblePage = state.currentPage
        }
        // SY <--

        // Hoisted so both the toolbar (select all/invert) and the content share one paging instance.
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        // SY -->
        val visibleManga = mangaList.itemSnapshotList.items.mapNotNull { it?.value?.first }
        // SY <--
        val navigateUp: () -> Unit = {
            // 搜索 / 点了 tag / 改过筛选之后先撤销浏览状态；撤不了再走原来的逻辑。
            if (!screenModel.undoBrowsingStateBeforeLeaving()) {
                if (!state.isUserQuery && state.toolbarQuery != null) {
                    screenModel.setToolbarQuery(null)
                } else {
                    navigator.pop()
                }
            }
        }

        // SY -->
        val context = LocalContext.current
        // SY <--

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        // SY -->
        // 返回键：多选 → 退出多选；搜索 / 点了 tag / 改过筛选 → 先撤销浏览状态回到进入之前的列表
        // （不能一步掉回图源列表）；都没有才 pop。
        BackHandler(enabled = state.selectionMode || screenModel.canUndoBrowsingState()) {
            if (state.selectionMode) {
                screenModel.toggleSelectionMode()
            } else if (!screenModel.undoBrowsingStateBeforeLeaving()) {
                navigator.pop()
            }
        }
        // SY <--

        // SY -->
        var showAddBrowseTagDialog by remember { mutableStateOf(false) }
        var listingRowHeightPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
        val screenWidthPx = with(density) { screenWidthDp.toPx() }

        // 点击顶部图源名称时递增，通知当前页面的漫画列表回到顶部
        var scrollToTopNonce by remember { mutableStateOf(0) }

        val tagListState = rememberLazyListState()
        val browseTagsState = remember { screenModel.browseSourceTags.toMutableStateList() }
        val tagReorderableState = rememberReorderableLazyListState(tagListState) { from, to ->
            val fromIndex = (from.index - 1).coerceAtLeast(0)
            val toIndex = (to.index - 1).coerceAtLeast(0)
            val tags = browseTagsState.toMutableList()
            if (fromIndex in tags.indices) {
                val item = tags.removeAt(fromIndex)
                tags.add(toIndex.coerceAtMost(tags.size), item)
                browseTagsState.clear()
                browseTagsState.addAll(tags)
                screenModel.reorderBrowseSourceTags(tags)
            }
        }
        LaunchedEffect(screenModel.browseSourceTags) {
            if (!tagReorderableState.isAnyItemDragging) {
                browseTagsState.clear()
                browseTagsState.addAll(screenModel.browseSourceTags)
            }
        }
        // SY <--

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        // SY -->
        // 打开图源设置（工具栏溢出菜单里的那一项，也是空列表里「修改插件设置」的目标）
        val onSourceSettingsClick: () -> Unit = { navigator.push(SourcePreferencesScreen(sourceId)) }
        // 网络图源不提供 WebView 入口：它的 getHomeUrl() 指向 WebDAV 根目录，
        // 用 WebView 打开不过是把目录列出来。传 null 时工具栏和溢出菜单里都不会出现这一项。
        val onWebViewClick: (() -> Unit)? = if (sourceId == NetworkSource.ID) {
            null
        } else {
            f@{
                val source = screenModel.source as? HttpSource ?: return@f
                navigator.push(
                    WebViewScreen(
                        url = source.getHomeUrl(),
                        initialTitle = source.name,
                        sourceId = source.id,
                    ),
                )
            }
        }
        // SY <--

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.getHomeUrl()
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = onSourceSettingsClick,
                        // SY -->
                        // 点击图源名称让漫画列表回到顶部
                        onClickSourceTitle = { scrollToTopNonce++ },
                        // SY <--
                        onSearch = screenModel::search,
                        searchHistory = state.searchHistory,
                        onSearchHistoryClick = screenModel::search,
                        onSearchHistoryAddToTag = { screenModel.addBrowseSourceTag(it) },
                        onClearSearchHistory = screenModel::clearSearchHistory,
                        // SY -->
                        selectionMode = state.selectionMode,
                        selectionCount = state.selection.size,
                        onToggleSelectionMode = screenModel::toggleSelectionMode,
                        onCancelSelectionMode = screenModel::toggleSelectionMode,
                        onSelectAll = { screenModel.selectAllVisible(visibleManga) },
                        onInvertSelection = { screenModel.invertSelectionVisible(visibleManga) },
                        onAddSelectionToLibrary = screenModel::bulkAddToLibrary,
                        // SY <--
                    )

                    // SY -->
                    if (!state.selectionMode) {
                        // SY <--
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small)
                                // SY -->
                                .onSizeChanged { listingRowHeightPx = it.height },
                            // SY <--
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing == Listing.Popular,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Popular)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.popular))
                                },
                            )
                            if (screenModel.source.supportsLatest) {
                                FilterChip(
                                    selected = state.listing == Listing.Latest,
                                    onClick = {
                                        screenModel.resetFilters()
                                        screenModel.setListing(Listing.Latest)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.NewReleases,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(MR.strings.latest))
                                    },
                                )
                            }
                            if (/* SY --> */ state.filterable /* SY <-- */) {
                                FilterChip(
                                    selected = state.listing is Listing.Search,
                                    onClick = screenModel::openFilterSheet,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        // SY -->
                                        Text(
                                            text = if (state.filters.isNotEmpty()) {
                                                stringResource(MR.strings.action_filter)
                                            } else {
                                                stringResource(MR.strings.action_search)
                                            },
                                        )
                                        // SY <--
                                    },
                                )
                            }
                            // SY -->
                            FilterChip(
                                selected = screenModel.browseSwipeToPage,
                                onClick = { screenModel.browseSwipeToPage = !screenModel.browseSwipeToPage },
                                label = {
                                    Text(text = stringResource(SYMR.strings.browse_page_swipe))
                                },
                            )
                            // SY <--
                        }

                        // SY -->
                        HorizontalDivider()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { listingRowHeightPx.toDp() }),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LazyRow(
                                    state = tagListState,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = MaterialTheme.padding.small),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                                ) {
                                    items(
                                        items = browseTagsState,
                                        key = { it },
                                    ) { tag ->
                                        ReorderableItem(tagReorderableState, key = tag) {
                                            BrowseSourceTagChip(
                                                label = tag,
                                                onClick = { screenModel.searchGenre(tag) },
                                                onDoubleClick = { screenModel.deleteBrowseSourceTag(tag) },
                                                modifier = Modifier.longPressDraggableHandle(),
                                            )
                                        }
                                    }
                                }
                                BrowseSourceTagChip(
                                    label = "+",
                                    onClick = { showAddBrowseTagDialog = true },
                                    onDoubleClick = {},
                                    modifier = Modifier.padding(end = MaterialTheme.padding.small),
                                )
                            }
                        }
                        // SY <--

                        HorizontalDivider()
                        // SY -->
                    }
                    // SY <--
                }
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                            )
                            .padding(
                                horizontal = MaterialTheme.padding.small,
                                vertical = MaterialTheme.padding.extraSmall,
                            ),
                    ) {
                        IconButton(
                            onClick = { screenModel.goToPage(visiblePage - 1) },
                            enabled = visiblePage > 1,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(72.dp, 36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronLeft,
                                contentDescription = stringResource(SYMR.strings.browse_page_previous),
                            )
                        }
                        // SY -->
                        Text(
                            text = stringResource(SYMR.strings.browse_page_indicator, visiblePage),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clickable {
                                    screenModel.setDialog(BrowseSourceScreenModel.Dialog.JumpToPage(visiblePage))
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            onClick = { screenModel.goToPage(visiblePage + 1) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(72.dp, 36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = stringResource(SYMR.strings.browse_page_next),
                            )
                        }
                        // SY <--
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            val onMangaClickHandler: (Manga) -> Unit = { manga ->
                if (state.selectionMode) {
                    screenModel.toggleSelection(manga)
                } else {
                    navigator.push(MangaScreen(manga.id, true, smartSearchConfig))
                }
            }
            val onMangaLongClickHandler: (Manga) -> Unit = { manga ->
                if (state.selectionMode) {
                    screenModel.toggleSelection(manga)
                } else {
                    scope.launchIO {
                        val duplicates = screenModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                            duplicates.isNotEmpty() -> screenModel.setDialog(
                                BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> screenModel.addFavorite(manga)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }

            // SY -->
            // Each swipeable page owns a MovableContent keyed by its page number. When swiping to
            // a neighbouring page, that page's collected LazyPagingItems moves with it instead of
            // being recreated, so an already-prefetched page shows immediately without a blank or
            // loading flash.
            val swipePageSlots = remember(state.listing) {
                mutableMapOf<Int, @Composable (SwipePageSlotParams) -> Unit>()
            }
            for (page in (visiblePage - 1)..(visiblePage + 1)) {
                key(page) {
                    if (!swipePageSlots.containsKey(page)) {
                        swipePageSlots[page] = movableContentOf<SwipePageSlotParams> { params ->
                            val pageListState = rememberLazyListState()
                            val pageGridState = rememberLazyGridState()
                            BrowseSourceContentPage(
                                screenModel = screenModel,
                                mangaList = remember { screenModel.mangaPagerFlow(page) }
                                    .collectAsLazyPagingItemsFlow(),
                                contentPadding = params.contentPadding,
                                snackbarHostState = snackbarHostState,
                                selection = params.selection,
                                onMangaClick = params.onMangaClick,
                                onMangaLongClick = params.onMangaLongClick,
                                onWebViewClick = onWebViewClick,
                                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                                onLocalSourceHelpClick = onHelpClick,
                                onSourceSettingsClick = onSourceSettingsClick,
                                listState = pageListState,
                                gridState = pageGridState,
                                scrollToTopNonce = params.scrollToTopNonce,
                                isCurrentPage = params.isCurrentPage,
                                skipInitialLoading = params.skipInitialLoading,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { translationX = params.translationX },
                            )
                        }
                    }
                }
            }
            // SY <--

            // SY -->
            if (screenModel.browseSwipeToPage) {
                // Swipe pages are rendered OUTSIDE the [state.currentPage]-keyed block below.
                // Completing a swipe updates [state.currentPage]; keying the movable page content on
                // it would tear down the already-loaded LazyPagingItems and blank out the view for a
                // single frame while it re-enters the Loading state.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(visiblePage, screenModel.browseSwipeToPage) {
                            // 在 Initial 阶段自行识别横向拖动：这样列表/条目（多选模式下的
                            // 点击、长按等）无法抢先消费横滑事件，侧滑翻页在多选模式下同样可用。
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dragging = false
                                var lastX = down.position.x
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - lastX
                                    lastX = change.position.x
                                    if (!dragging && abs(dx) > touchSlop) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        // 抢占横向手势，列表与条目不再收到该滑动
                                        change.consume()
                                        if (!swipeOffset.isRunning) {
                                            scope.launch { swipeOffset.snapTo(swipeOffset.value + dx) }
                                        }
                                    } else if (change.isConsumed) {
                                        // 已被子节点消费（例如纵向滚动），本次不参与翻页
                                        break
                                    }
                                }
                                if (dragging && !swipeOffset.isRunning) {
                                    scope.launch {
                                        val threshold = screenWidthPx * 0.25f
                                        when {
                                            swipeOffset.value > threshold && visiblePage > 1 -> {
                                                swipeOffset.animateTo(screenWidthPx)
                                                val targetPage = visiblePage - 1
                                                swipeOffset.snapTo(0f)
                                                visiblePage = targetPage
                                                screenModel.goToPage(targetPage)
                                            }
                                            swipeOffset.value < -threshold -> {
                                                swipeOffset.animateTo(-screenWidthPx)
                                                val targetPage = visiblePage + 1
                                                swipeOffset.snapTo(0f)
                                                visiblePage = targetPage
                                                screenModel.goToPage(targetPage)
                                            }
                                            else -> swipeOffset.animateTo(0f)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val previousPage = (visiblePage - 1).coerceAtLeast(1)
                        swipePageSlots[previousPage]?.let { slot ->
                            slot(
                                SwipePageSlotParams(
                                    translationX = -screenWidthPx + swipeOffset.value,
                                    skipInitialLoading = screenModel.isPageCached(previousPage),
                                    contentPadding = paddingValues,
                                    selection = state.selection,
                                    onMangaClick = onMangaClickHandler,
                                    onMangaLongClick = onMangaLongClickHandler,
                                    scrollToTopNonce = 0,
                                    isCurrentPage = false,
                                ),
                            )
                        }
                        swipePageSlots[visiblePage]?.let { slot ->
                            slot(
                                SwipePageSlotParams(
                                    translationX = swipeOffset.value,
                                    skipInitialLoading = screenModel.isPageCached(visiblePage),
                                    contentPadding = paddingValues,
                                    selection = state.selection,
                                    onMangaClick = onMangaClickHandler,
                                    onMangaLongClick = onMangaLongClickHandler,
                                    scrollToTopNonce = scrollToTopNonce,
                                    isCurrentPage = true,
                                ),
                            )
                        }
                        swipePageSlots[visiblePage + 1]?.let { slot ->
                            slot(
                                SwipePageSlotParams(
                                    translationX = screenWidthPx + swipeOffset.value,
                                    skipInitialLoading = screenModel.isPageCached(visiblePage + 1),
                                    contentPadding = paddingValues,
                                    selection = state.selection,
                                    onMangaClick = onMangaClickHandler,
                                    onMangaLongClick = onMangaLongClickHandler,
                                    scrollToTopNonce = 0,
                                    isCurrentPage = false,
                                ),
                            )
                        }
                    }
                }
            } else {
                // SY <--
                key(state.listing, state.currentPage) {
                    val listState = rememberLazyListState()
                    val gridState = rememberLazyGridState()
                    // SY -->
                    val isEhentaiList = screenModel.source.isEhBasedSource() && screenModel.ehentaiBrowseDisplayMode
                    val firstVisibleIndex = when {
                        isEhentaiList -> listState.firstVisibleItemIndex
                        screenModel.displayMode == LibraryDisplayMode.List -> listState.firstVisibleItemIndex
                        else -> gridState.firstVisibleItemIndex
                    }
                    val scrolledPage = screenModel.pageForIndex(firstVisibleIndex, pageSizes)
                    LaunchedEffect(scrolledPage) { visiblePage = scrolledPage }

                    // SY -->
                    // 关闭“侧滑翻页”后不再响应横滑，只保留纵向滚动
                    // SY <--
                    BrowseSourceContentPage(
                        screenModel = screenModel,
                        mangaList = mangaList,
                        contentPadding = paddingValues,
                        snackbarHostState = snackbarHostState,
                        selection = state.selection,
                        onMangaClick = onMangaClickHandler,
                        onMangaLongClick = onMangaLongClickHandler,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                        onLocalSourceHelpClick = onHelpClick,
                        onSourceSettingsClick = onSourceSettingsClick,
                        listState = listState,
                        gridState = gridState,
                        scrollToTopNonce = scrollToTopNonce,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // SY -->
            }
            // SY <--
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        // SY -->
        if (showAddBrowseTagDialog) {
            CategoryCreateDialog(
                onDismissRequest = { showAddBrowseTagDialog = false },
                onCreate = { screenModel.addBrowseSourceTag(it) },
                categories = screenModel.browseSourceTags,
                title = stringResource(SYMR.strings.add_tag),
                alreadyExistsError = SYMR.strings.error_tag_exists,
            )
        }
        // SY <--
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                    // SY -->
                    startExpanded = screenModel.startExpanded,
                    onSave = screenModel::onSaveSearch,
                    savedSearches = state.savedSearches,
                    onSavedSearch = { search ->
                        screenModel.onSavedSearch(search) {
                            context.toast(it)
                        }
                    },
                    onSavedSearchPress = screenModel::onSavedSearchPress,
                    openMangaDexRandom = if (screenModel.sourceIsMangaDex) {
                        {
                            screenModel.onMangaDexRandom {
                                navigator.replace(
                                    BrowseSourceScreen(
                                        sourceId,
                                        "id:$it",
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    openMangaDexFollows = if (screenModel.sourceIsMangaDex) {
                        {
                            navigator.replace(MangaDexFollowsScreen(sourceId))
                        }
                    } else {
                        null
                    },
                    // SY <--
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            // SY -->
            is BrowseSourceScreenModel.Dialog.BulkChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.bulkAddToLibrary(dialog.mangas, include)
                        screenModel.toggleSelectionMode()
                    },
                )
            }
            // SY <--
            is BrowseSourceScreenModel.Dialog.CreateSavedSearch -> SavedSearchCreateDialog(
                onDismissRequest = onDismissRequest,
                currentSavedSearches = dialog.currentSavedSearches,
                saveSearch = screenModel::saveSearch,
            )
            is BrowseSourceScreenModel.Dialog.DeleteSavedSearch -> SavedSearchDeleteDialog(
                onDismissRequest = onDismissRequest,
                name = dialog.name,
                deleteSavedSearch = {
                    screenModel.deleteSearch(dialog.idToDelete)
                },
            )
            is BrowseSourceScreenModel.Dialog.JumpToPage -> BrowseSourceJumpToPageDialog(
                onDismissRequest = onDismissRequest,
                currentPage = dialog.currentPage,
                onConfirm = { pagingTargetPage ->
                    screenModel.goToPage(pagingTargetPage)
                },
            )
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

// SY -->
// Parameters passed to each swipeable page's MovableContent when invoking it. Kept in a dedicated
// type so the movable content only recomposes the bits that actually change per frame.
private data class SwipePageSlotParams(
    val translationX: Float,
    val skipInitialLoading: Boolean,
    val contentPadding: PaddingValues,
    val selection: List<Manga>,
    val onMangaClick: (Manga) -> Unit,
    val onMangaLongClick: (Manga) -> Unit,
    // 仅当前可见页接收"回到顶部"触发，其它预加载页传 0
    val scrollToTopNonce: Int,
    val isCurrentPage: Boolean,
)

@Composable
private fun BrowseSourceTagChip(
    label: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FilterChipDefaults.filterChipColors()
    Surface(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onDoubleClick = onDoubleClick,
        ),
        shape = FilterChipDefaults.shape,
        color = colors.containerColor,
        contentColor = colors.labelColor,
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(FilterChipDefaults.Height)
                .padding(horizontal = 16.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// SY -->
@Composable
private fun BrowseSourceContentPage(
    screenModel: BrowseSourceScreenModel,
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    selection: List<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onWebViewClick: (() -> Unit)?,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    // 网络图源用：「在 WebView 中打开」的位置换成「修改插件设置」
    onSourceSettingsClick: (() -> Unit)? = null,
    listState: LazyListState,
    gridState: LazyGridState,
    scrollToTopNonce: Int = 0,
    isCurrentPage: Boolean = true,
    skipInitialLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        BrowseSourceContent(
            source = screenModel.source,
            mangaList = mangaList,
            columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
            ehentaiBrowseDisplayMode = screenModel.ehentaiBrowseDisplayMode,
            displayMode = screenModel.displayMode,
            snackbarHostState = snackbarHostState,
            contentPadding = contentPadding,
            onWebViewClick = onWebViewClick,
            onHelpClick = onHelpClick,
            onLocalSourceHelpClick = onLocalSourceHelpClick,
            onSourceSettingsClick = onSourceSettingsClick,
            selection = selection,
            listState = listState,
            gridState = gridState,
            scrollToTopNonce = scrollToTopNonce,
            isCurrentPage = isCurrentPage,
            skipInitialLoading = skipInitialLoading,
            onMangaClick = onMangaClick,
            onMangaLongClick = onMangaLongClick,
        )
    }
}
// SY <--
