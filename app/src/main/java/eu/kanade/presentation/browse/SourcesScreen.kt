package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.browse.components.IndexBarDragTarget
import eu.kanade.presentation.browse.components.IndexBarEdgeHint
import eu.kanade.presentation.browse.components.IndexBarEdgeScroller
import eu.kanade.presentation.browse.components.IndexBarLocateHint
import eu.kanade.presentation.browse.components.LanguageTagBar
import eu.kanade.presentation.browse.components.LetterIndexBar
import eu.kanade.presentation.browse.components.LocalIndexBarBottomBarVisible
import eu.kanade.presentation.browse.components.NsfwFilter
import eu.kanade.presentation.browse.util.compareByInitial
import eu.kanade.presentation.browse.util.initialOfTitle
import eu.kanade.presentation.components.LocalTabScrollToTopState
import eu.kanade.presentation.manga.components.DotSeparatorNoSpaceText
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.flash
import eu.kanade.presentation.util.itemIndexAt
import eu.kanade.presentation.util.onAnyTouch
import eu.kanade.presentation.util.pointerHighlight
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SourcesScreen(
    state: SourcesScreenModel.State,
    contentPadding: PaddingValues,
    onClickItem: (Source, Listing) -> Unit,
    onClickPin: (Source) -> Unit,
    onLongClickItem: (Source) -> Unit,
    // SY -->
    installedLanguages: List<String> = emptyList(),
    nsfwFilter: NsfwFilter? = null,
    onNsfwFilterClick: () -> Unit = {},
    onMoveLanguage: ((String, Int) -> Unit)? = null,
    // SY <--
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // SY -->
    // 再次点击顶部当前标签时，列表带动画回到顶部
    val tabScrollToTop = LocalTabScrollToTopState.current
    LaunchedEffect(tabScrollToTop.nonce) {
        if (tabScrollToTop.nonce > 0 && tabScrollToTop.isCurrentPage) {
            listState.animateScrollToItem(0)
        }
    }
    // SY <--

    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            if (state.searchQuery.isNullOrEmpty()) MR.strings.source_empty_screen else MR.strings.no_results_found,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            // SY -->
            val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
            val indexBarPosition by libraryPreferences.indexBarPosition.collectAsState()
            val indexBarAlign = when (indexBarPosition) {
                LibraryPreferences.IndexBarPosition.LEFT -> Alignment.CenterStart
                LibraryPreferences.IndexBarPosition.RIGHT -> Alignment.CenterEnd
                LibraryPreferences.IndexBarPosition.OFF -> Alignment.CenterEnd
            }
            var activeLetter by remember { mutableStateOf<String?>(null) }
            var targetSourceId by remember { mutableStateOf<Long?>(null) }
            var targetNonce by remember { mutableStateOf(0) }
            // 手指指中/定位到的图源，用于绘制醒目边框
            var highlightSourceId by remember { mutableStateOf<Long?>(null) }
            // 进入检索前的位置，未命中任何图源时恢复
            var savedIndex by remember { mutableStateOf(-1) }
            var savedOffset by remember { mutableStateOf(0) }
            // 手指当前所在区域（滑入列表后才有值），用于上下边缘遮罩与滚动
            var dragTarget by remember { mutableStateOf<IndexBarDragTarget?>(null) }
            // 语言分类栏的位置与高度，用于让顶部遮罩正好覆盖它
            var languageBarTopPx by remember { mutableStateOf(0) }
            var languageBarHeightPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            val draggingInList = dragTarget != null && dragTarget != IndexBarDragTarget.Bar
            // 没有底部栏时（平板布局或导航栏隐藏），列表自己画"下移"区域，高度与"上移"区域一致
            val bottomBarVisible = LocalIndexBarBottomBarVisible.current
            val bottomZoneHeight = if (bottomBarVisible) {
                0.dp
            } else {
                with(density) { languageBarHeightPx.toDp() }.takeIf { it > 0.dp } ?: 48.dp
            }
            // 手指停在分类栏/底部栏区域时缓慢滚动列表，并让底部导航栏显示"下移"遮罩
            IndexBarEdgeScroller(
                dragTarget = dragTarget,
                scrollBy = { delta -> listState.scrollBy(delta) },
            )
            // SY <--
            // 索引条滑动期间：隐藏语言分组、按图源名首字母排序、同名图源去重
            val sliding = activeLetter != null
            val flatSortedItems = remember(state.items, sliding) {
                if (!sliding) {
                    emptyList()
                } else {
                    state.items.filterIsInstance<SourceUiModel.Item>()
                        .distinctBy { it.source.name }
                        .sortedWith(compareByInitial { it.source.name })
                }
            }
            // 滑动到某个字母时，把列表跳转到该字母的首个条目
            LaunchedEffect(activeLetter) {
                val letter = activeLetter ?: return@LaunchedEffect
                // 进入检索时先记住当前位置（只在本次检索开始时记一次）
                if (savedIndex < 0) {
                    savedIndex = listState.firstVisibleItemIndex
                    savedOffset = listState.firstVisibleItemScrollOffset
                }
                val idx = flatSortedItems.indexOfFirst { initialOfTitle(it.source.name) == letter[0] }
                if (idx >= 0) {
                    listState.scrollToItem(idx)
                }
            }
            // 松手后：恢复排序并把列表滚动到手指松开时选中的图源；
            // 没有指向任何图源时不做定位与闪烁，直接回到检索前的位置
            LaunchedEffect(targetNonce, activeLetter) {
                if (targetNonce == 0 || activeLetter != null) return@LaunchedEffect
                // 等待列表切回原排序并完成一次布局，避免按旧排序的索引错位
                withFrameNanos { }
                if (targetSourceId == null) {
                    if (savedIndex >= 0) {
                        listState.scrollToItem(savedIndex, savedOffset)
                        savedIndex = -1
                    }
                    return@LaunchedEffect
                }
                val idx = state.items.indexOfFirst { it is SourceUiModel.Item && it.source.id == targetSourceId }
                savedIndex = -1
                if (idx >= 0) {
                    listState.scrollToItem(idx)
                }
            }
            val renderSourceItem: @Composable context(LazyItemScope) (SourceUiModel.Item) -> Unit = { model ->
                SourceItem(
                    modifier = Modifier
                        .animateItemFastScroll()
                        .pointerHighlight(model.source.id == highlightSourceId),
                    source = model.source,
                    isNsfw = model.isNsfw,
                    showLatest = state.showLatest,
                    showPin = state.showPin,
                    onClickItem = onClickItem,
                    onLongClickItem = onLongClickItem,
                    onClickPin = onClickPin,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(
                        top = contentPadding.calculateTopPadding(),
                        // 平板布局没有底部栏：内容（含索引条）贴到屏幕下边缘，由列表自己画"下移"区域；
                        // 底部安全区改由列表内容内边距保留
                        bottom = if (bottomBarVisible) contentPadding.calculateBottomPadding() else 0.dp,
                        start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                        end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    )
                    // 任何操作都清除定位后的高亮边框
                    .onAnyTouch { highlightSourceId = null },
            ) {
                if (installedLanguages.isNotEmpty()) {
                    // 记录语言分类栏位置与高度，供索引条拖动的顶部遮罩使用
                    Box(
                        modifier = Modifier.onGloballyPositioned { coords ->
                            languageBarTopPx = coords.positionInParent().y.toInt()
                            languageBarHeightPx = coords.size.height
                        },
                    ) {
                        LanguageTagBar(
                            languages = installedLanguages,
                            onLanguageClick = { lang ->
                                val target = state.items.indexOfFirst {
                                    it is SourceUiModel.Header && it.language == lang && !it.isCategory
                                }
                                if (target != -1) {
                                    scope.launch { listState.animateScrollToItem(target) }
                                }
                            },
                            nsfwFilter = nsfwFilter,
                            onNsfwFilterClick = onNsfwFilterClick,
                            onMoveLanguage = onMoveLanguage,
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                ScrollbarLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = topSmallPaddingValues + PaddingValues(
                        bottom = if (bottomBarVisible) 0.dp else contentPadding.calculateBottomPadding(),
                    ),
                    // 索引条在右侧时滚动条移到左侧；索引条滑动期间隐藏滚动条
                    scrollAtStart = indexBarPosition == LibraryPreferences.IndexBarPosition.RIGHT,
                    hideScrollbar = activeLetter != null,
                ) {
                    if (sliding) {
                        if (flatSortedItems.isNotEmpty()) {
                            items(
                                items = flatSortedItems,
                                contentType = { "item" },
                                key = { "index-flat-${it.source.key()}" },
                            ) { model ->
                                renderSourceItem(model)
                            }
                        }
                    } else {
                        items(
                            items = state.items,
                            contentType = {
                                when (it) {
                                    is SourceUiModel.Header -> "header"
                                    is SourceUiModel.Item -> "item"
                                }
                            },
                            key = {
                                when (it) {
                                    is SourceUiModel.Header -> it.hashCode()
                                    is SourceUiModel.Item -> "source-${it.source.key()}"
                                }
                            },
                        ) { model ->
                            when (model) {
                                is SourceUiModel.Header -> {
                                    SourceHeader(
                                        modifier = Modifier.animateItem(),
                                        language = model.language,
                                        // SY -->
                                        isCategory = model.isCategory,
                                        // SY <--
                                    )
                                }
                                is SourceUiModel.Item -> SourceItem(
                                    modifier = Modifier
                                        .animateItem()
                                        .flash(model.source.id == targetSourceId, targetNonce)
                                        .pointerHighlight(model.source.id == highlightSourceId),
                                    source = model.source,
                                    isNsfw = model.isNsfw,
                                    // SY -->
                                    showLatest = state.showLatest,
                                    showPin = state.showPin,
                                    // SY <--
                                    onClickItem = onClickItem,
                                    onLongClickItem = onLongClickItem,
                                    onClickPin = onClickPin,
                                )
                            }
                        }
                    }
                }
                    // SY -->
                // 手指还在索引条上滑动时，给列表盖半透明遮罩提示下一步；滑入列表后消失
                IndexBarLocateHint(
                    visible = activeLetter != null && !draggingInList,
                    text = "手指移到这里，松开即可定位",
                    modifier = Modifier.fillMaxSize(),
                )
                if (indexBarPosition != LibraryPreferences.IndexBarPosition.OFF) {
                    LetterIndexBar(
                        modifier = Modifier.fillMaxSize(),
                        position = indexBarAlign,
                        activeLetter = activeLetter,
                        hintText = "滑向图源松手即可定位",
                        bottomZoneHeight = bottomZoneHeight,
                        onActiveLetterChange = { activeLetter = it },
                        onDragTargetChange = { dragTarget = it },
                        onDragMoved = { pos ->
                            highlightSourceId = pos?.let { listState.itemIndexAt(it.y) }
                                ?.let { flatSortedItems.getOrNull(it)?.source?.id }
                        },
                        onDragFinished = { pos ->
                            val target = pos?.let { listState.itemIndexAt(it.y) }
                                ?.let { flatSortedItems.getOrNull(it) }
                            targetSourceId = target?.source?.id
                            // 定位到的图源保留高亮边框，直到有其它操作
                            highlightSourceId = target?.source?.id
                            targetNonce++
                            target != null
                        },
                    )
                }
                // SY <--
                }
            }
                // SY -->
                // 手指滑入列表期间：用外观色遮罩语言分类栏，居中提示"上移"
                if (draggingInList && languageBarHeightPx > 0) {
                    IndexBarEdgeHint(
                        text = "上移",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset { IntOffset(0, languageBarTopPx) }
                            .fillMaxWidth()
                            .height(with(density) { languageBarHeightPx.toDp() }),
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
                // SY <--
            }
        }
    }
}

@Composable
private fun SourceHeader(
    language: String,
    // SY -->
    isCategory: Boolean,
    // SY <--
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        // SY -->
        text = if (!isCategory) {
            LocaleHelper.getSourceDisplayName(language, context)
        } else {
            language
        },
        // SY <--
        modifier = modifier
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        style = MaterialTheme.typography.header,
    )
}

@Composable
private fun SourceItem(
    source: Source,
    isNsfw: Boolean,
    // SY -->
    showLatest: Boolean,
    showPin: Boolean,
    // SY <--
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickPin: (Source) -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseSourceItem(
        modifier = modifier,
        source = source,
        onClickItem = { onClickItem(source, Listing.Popular) },
        onLongClickItem = { onLongClickItem(source) },
        action = {
            if (source.supportsLatest /* SY --> */ && showLatest /* SY <-- */) {
                TextButton(onClick = { onClickItem(source, Listing.Latest) }) {
                    Text(
                        text = stringResource(MR.strings.latest),
                        style = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
            // SY -->
            if (showPin) {
                SourcePinButton(
                    isPinned = Pin.Pinned in source.pin,
                    onClick = { onClickPin(source) },
                )
            }
            // SY <--
        },
    ) { _, sourceLangString ->
        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = source.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (sourceLangString != null || isNsfw) {
                FlowRow(
                    modifier = Modifier.secondaryItemAlpha(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                        var hasAlreadyShownAnElement by remember { mutableStateOf(false) }
                        if (sourceLangString != null) {
                            hasAlreadyShownAnElement = true
                            Text(
                                text = sourceLangString,
                            )
                        }
                        if (isNsfw) {
                            if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                            hasAlreadyShownAnElement = true
                            Text(
                                text = stringResource(MR.strings.ext_nsfw_short).uppercase(),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
) {
    val icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
    val tint = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(
            alpha = SECONDARY_ALPHA,
        )
    }
    val description = if (isPinned) MR.strings.action_unpin else MR.strings.action_pin
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            tint = tint,
            contentDescription = stringResource(description),
        )
    }
}

@Composable
fun SourceOptionsDialog(
    source: Source,
    onClickPin: () -> Unit,
    onClickDisable: () -> Unit,
    // SY -->
    onClickSetCategories: (() -> Unit)?,
    onClickToggleDataSaver: (() -> Unit)?,
    // SY <--
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = source.visualName)
        },
        text = {
            Column {
                val textId = if (Pin.Pinned in source.pin) MR.strings.action_unpin else MR.strings.action_pin
                Text(
                    text = stringResource(textId),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                if (!source.isLocal()) {
                    Text(
                        text = stringResource(MR.strings.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                // SY -->
                if (onClickSetCategories != null) {
                    Text(
                        text = stringResource(MR.strings.categories),
                        modifier = Modifier
                            .clickable(onClick = onClickSetCategories)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                if (onClickToggleDataSaver != null) {
                    Text(
                        text = if (source.isExcludedFromDataSaver) {
                            stringResource(SYMR.strings.data_saver_stop_exclude)
                        } else {
                            stringResource(SYMR.strings.data_saver_exclude)
                        },
                        modifier = Modifier
                            .clickable(onClick = onClickToggleDataSaver)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                // SY <--
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}

sealed interface SourceUiModel {
    data class Item(val source: Source, val isNsfw: Boolean = false) : SourceUiModel
    data class Header(val language: String, val isCategory: Boolean) : SourceUiModel
}

// SY -->
@Composable
fun SourceCategoriesDialog(
    source: Source,
    categories: List<String>,
    onClickCategories: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val newCategories = remember(source) {
        mutableStateListOf<String>().also { it += source.categories }
    }
    AlertDialog(
        title = {
            Text(text = source.visualName)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                categories.forEach { category ->
                    LabeledCheckbox(
                        label = category,
                        checked = category in newCategories,
                        onCheckedChange = {
                            if (it) {
                                newCategories += category
                            } else {
                                newCategories -= category
                            }
                        },
                    )
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onClickCategories(newCategories.toList()) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
// SY <--
