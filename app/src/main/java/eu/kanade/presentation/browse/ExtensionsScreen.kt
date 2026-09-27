package eu.kanade.presentation.browse

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.browse.components.ExtensionIcon
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
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.manga.components.DotSeparatorNoSpaceText
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.flash
import eu.kanade.presentation.util.itemIndexAt
import eu.kanade.presentation.util.onAnyTouch
import eu.kanade.presentation.util.pointerHighlight
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ExtensionScreen(
    state: ExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onOpenWebView: (Extension.Available) -> Unit,
    onInstallExtension: (Extension.Available) -> Unit,
    onUninstallExtension: (Extension) -> Unit,
    onUpdateExtension: (Extension.Installed) -> Unit,
    onTrustExtension: (Extension.Untrusted) -> Unit,
    onOpenExtension: (Extension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
    onRefresh: () -> Unit,
    // SY -->
    nsfwFilter: NsfwFilter? = null,
    onNsfwFilterClick: () -> Unit = {},
    onMoveLanguage: ((String, Int) -> Unit)? = null,
    // SY <--
) {
    val navigator = LocalNavigator.currentOrThrow

    PullRefresh(
        refreshing = state.isRefreshing,
        onRefresh = onRefresh,
        enabled = !state.isLoading,
    ) {
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.isEmpty -> {
                val msg = if (!searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.empty_screen
                }
                EmptyScreen(
                    msg,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.extensionStores,
                            icon = Icons.Outlined.Settings,
                            onClick = { navigator.push(ExtensionStoresScreen()) },
                        ),
                    ),
                )
            }
            else -> {
                ExtensionContent(
                    state = state,
                    contentPadding = contentPadding,
                    onLongClickItem = onLongClickItem,
                    onClickItemCancel = onClickItemCancel,
                    onOpenWebView = onOpenWebView,
                    onInstallExtension = onInstallExtension,
                    onUninstallExtension = onUninstallExtension,
                    onUpdateExtension = onUpdateExtension,
                    onTrustExtension = onTrustExtension,
                    onOpenExtension = onOpenExtension,
                    onClickUpdateAll = onClickUpdateAll,
                    nsfwFilter = state.nsfwFilter,
                    onNsfwFilterClick = onNsfwFilterClick,
                    onMoveLanguage = onMoveLanguage,
                )
            }
        }
    }
}

@Composable
private fun ExtensionContent(
    state: ExtensionsScreenModel.State,
    contentPadding: PaddingValues,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onOpenWebView: (Extension.Available) -> Unit,
    onInstallExtension: (Extension.Available) -> Unit,
    onUninstallExtension: (Extension) -> Unit,
    onUpdateExtension: (Extension.Installed) -> Unit,
    onTrustExtension: (Extension.Untrusted) -> Unit,
    onOpenExtension: (Extension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
    nsfwFilter: NsfwFilter? = null,
    onNsfwFilterClick: () -> Unit = {},
    onMoveLanguage: ((String, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    var trustState by remember { mutableStateOf<Extension.Untrusted?>(null) }
    val installGranted = rememberRequestPackageInstallsPermissionState(initialValue = true)
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

    // SY -->
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val indexBarPosition by libraryPreferences.indexBarPosition.collectAsState()
    val indexBarAlign = when (indexBarPosition) {
        LibraryPreferences.IndexBarPosition.LEFT -> Alignment.CenterStart
        LibraryPreferences.IndexBarPosition.RIGHT -> Alignment.CenterEnd
        LibraryPreferences.IndexBarPosition.OFF -> Alignment.CenterEnd
    }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var targetPkgName by remember { mutableStateOf<String?>(null) }
    var targetNonce by remember { mutableStateOf(0) }
    // 手指指中/定位到的插件，用于绘制醒目边框
    var highlightPkgName by remember { mutableStateOf<String?>(null) }
    // 进入检索前的位置，未命中任何插件时恢复
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

    val showPermissionWarning = !installGranted && state.installer?.requiresSystemPermission == true
    val showLanguageTagBar = state.enabledLanguages.isNotEmpty()
    val firstContentIndex = if (showPermissionWarning) 1 else 0
    val languageHeaderIndices = remember(state.items, showPermissionWarning) {
        buildMap {
            var index = firstContentIndex
            state.items.forEach { (header, items) ->
                if (header is ExtensionUiModel.Header.Text) {
                    put(header.text, index)
                }
                index += 1 + items.size
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        // 任何操作都清除定位后的高亮边框
        modifier = Modifier.onAnyTouch { highlightPkgName = null },
    ) {
        if (showLanguageTagBar) {
            // 记录语言分类栏位置与高度，供索引条拖动的顶部遮罩使用
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    languageBarTopPx = coords.positionInParent().y.toInt()
                    languageBarHeightPx = coords.size.height
                },
            ) {
            LanguageTagBar(
                languages = LocaleHelper.sortLanguages(state.enabledLanguages, state.languageOrder),
                onLanguageClick = { lang ->
                    val target = if (lang == "all") {
                        firstContentIndex
                    } else {
                        languageHeaderIndices[LocaleHelper.getSourceDisplayName(lang, context)]
                    }
                    if (target != null) {
                        scope.launch { listState.animateScrollToItem(target) }
                    }
                },
                nsfwFilter = nsfwFilter,
                onNsfwFilterClick = onNsfwFilterClick,
                onMoveLanguage = onMoveLanguage,
            )
            }
        }

        // 索引条滑动期间：隐藏语言分组、按插件名首字母排序、同名多语言插件去重
        val sliding = activeLetter != null
        val flatSortedItems = remember(state.items, sliding) {
            if (!sliding) {
                emptyList()
            } else {
                state.items.values.flatten()
                    .filterIsInstance<ExtensionUiModel.Item>()
                    .distinctBy { it.extension.name }
                    .sortedWith(compareByInitial { it.extension.name })
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
            val idx = flatSortedItems.indexOfFirst { initialOfTitle(it.extension.name) == letter[0] }
            if (idx >= 0) {
                listState.scrollToItem(firstContentIndex + idx)
            }
        }
        // 松手后：恢复分组布局并把列表滚动到手指松开时选中的插件；
        // 没有指向任何插件时不做定位与闪烁，直接回到检索前的位置
        LaunchedEffect(targetNonce, activeLetter) {
            if (targetNonce == 0 || activeLetter != null) return@LaunchedEffect
            // 等待列表切回原分组布局并完成一次布局，避免按旧排序的索引错位
            withFrameNanos { }
            if (targetPkgName == null) {
                if (savedIndex >= 0) {
                    listState.scrollToItem(savedIndex, savedOffset)
                    savedIndex = -1
                }
                return@LaunchedEffect
            }
            var target = -1
            var running = firstContentIndex
            for ((_, items) in state.items) {
                val i = items.indexOfFirst { it.extension.pkgName == targetPkgName }
                if (i >= 0) {
                    target = running + 1 + i
                    break
                }
                running += 1 + items.size
            }
            savedIndex = -1
            if (target >= 0) {
                listState.scrollToItem(target)
            }
        }
        val renderExtensionItem: @Composable context(LazyItemScope) (ExtensionUiModel.Item) -> Unit = { item ->
            ExtensionItem(
                modifier = Modifier
                    .animateItemFastScroll()
                    .flash(item.extension.pkgName == targetPkgName, targetNonce)
                    .pointerHighlight(item.extension.pkgName == highlightPkgName),
                item = item,
                onClickItem = {
                    when (it) {
                        is Extension.Available -> onInstallExtension(it)
                        is Extension.Installed -> onOpenExtension(it)
                        is Extension.Untrusted -> {
                            trustState = it
                        }
                    }
                },
                onLongClickItem = onLongClickItem,
                onClickItemSecondaryAction = {
                    when (it) {
                        is Extension.Available -> onOpenWebView(it)
                        is Extension.Installed -> onOpenExtension(it)
                        else -> {}
                    }
                },
                onClickItemCancel = onClickItemCancel,
                onClickItemAction = {
                    when (it) {
                        is Extension.Available -> onInstallExtension(it)
                        is Extension.Installed -> {
                            if (it.hasUpdate) {
                                onUpdateExtension(it)
                            } else {
                                onOpenExtension(it)
                            }
                        }
                        is Extension.Untrusted -> {
                            trustState = it
                        }
                    }
                },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            FastScrollLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding + topSmallPaddingValues,
                // 索引条在右侧时滚动条移到左侧；索引条滑动期间隐藏滚动条
                scrollAtStart = indexBarPosition == LibraryPreferences.IndexBarPosition.RIGHT,
                hideScrollbar = activeLetter != null,
            ) {
                if (showPermissionWarning) {
                    item(key = "extension-permissions-warning") {
                        WarningBanner(
                            textRes = MR.strings.ext_permission_install_apps_warning,
                            modifier = Modifier.clickable {
                                context.launchRequestPackageInstallsPermission()
                            },
                        )
                    }
                }

                if (sliding) {
                    if (flatSortedItems.isNotEmpty()) {
                        items(
                            items = flatSortedItems,
                            contentType = { "item" },
                            key = { item -> "index-flat-${item.extension.name}-${item.extension.lang}" },
                        ) { item ->
                            renderExtensionItem(item)
                        }
                    }
                } else {
                    state.items.forEach { (header, items) ->
                        item(
                            contentType = "header",
                            key = "extensionHeader-${header.hashCode()}",
                        ) {
                            when (header) {
                                is ExtensionUiModel.Header.Resource -> {
                                    val action: @Composable RowScope.() -> Unit =
                                        if (header.textRes == MR.strings.ext_updates_pending) {
                                            {
                                                Button(onClick = { onClickUpdateAll() }) {
                                                    Text(
                                                        text = stringResource(MR.strings.ext_update_all),
                                                        style = LocalTextStyle.current.copy(
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                        ),
                                                    )
                                                }
                                            }
                                        } else {
                                            {}
                                        }
                                    ExtensionHeader(
                                        textRes = header.textRes,
                                        modifier = Modifier.animateItemFastScroll(),
                                        action = action,
                                    )
                                }
                                is ExtensionUiModel.Header.Text -> {
                                    ExtensionHeader(
                                        text = header.text,
                                        modifier = Modifier.animateItemFastScroll(),
                                    )
                                }
                            }
                        }

                        items(
                            items = items,
                            contentType = { "item" },
                            key = { item ->
                                when (item.extension) {
                                    is Extension.Untrusted -> "extension-untrusted-${item.hashCode()}"
                                    is Extension.Installed -> "extension-installed-${item.hashCode()}"
                                    is Extension.Available -> "extension-available-${item.hashCode()}"
                                }
                            },
                        ) { item ->
                            renderExtensionItem(item)
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
                    hintText = "滑向插件松手即可定位",
                    bottomZoneHeight = bottomZoneHeight,
                    onActiveLetterChange = { activeLetter = it },
                    onDragTargetChange = { dragTarget = it },
                    onDragMoved = { pos ->
                        highlightPkgName = pos?.let { listState.itemIndexAt(it.y) }
                            ?.let { flatSortedItems.getOrNull(it - firstContentIndex)?.extension?.pkgName }
                    },
                    onDragFinished = { pos ->
                        val target = pos?.let { listState.itemIndexAt(it.y) }
                            ?.let { flatSortedItems.getOrNull(it - firstContentIndex) }
                        targetPkgName = target?.extension?.pkgName
                        // 定位到的插件保留高亮边框，直到有其它操作
                        highlightPkgName = target?.extension?.pkgName
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
    if (trustState != null) {
        ExtensionTrustDialog(
            onClickConfirm = {
                onTrustExtension(trustState!!)
                trustState = null
            },
            onClickDismiss = {
                onUninstallExtension(trustState!!)
                trustState = null
            },
            onDismissRequest = {
                trustState = null
            },
        )
    }
}

@Composable
private fun ExtensionItem(
    item: ExtensionUiModel.Item,
    onClickItem: (Extension) -> Unit,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onClickItemAction: (Extension) -> Unit,
    onClickItemSecondaryAction: (Extension) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (extension, installStep) = item
    BaseBrowseItem(
        modifier = modifier
            .combinedClickable(
                onClick = { onClickItem(extension) },
                onLongClick = { onLongClickItem(extension) },
            ),
        onClickItem = { onClickItem(extension) },
        onLongClickItem = { onLongClickItem(extension) },
        icon = {
            Box(
                modifier = Modifier
                    .size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                val idle = installStep.isCompleted()
                if (!idle) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 2.dp,
                    )
                }

                val padding by animateDpAsState(targetValue = if (idle) 0.dp else 8.dp)
                ExtensionIcon(
                    extension = extension,
                    modifier = Modifier
                        .matchParentSize()
                        .padding(padding),
                )
            }
        },
        action = {
            ExtensionItemActions(
                extension = extension,
                installStep = installStep,
                onClickItemCancel = onClickItemCancel,
                onClickItemAction = onClickItemAction,
                onClickItemSecondaryAction = onClickItemSecondaryAction,
            )
        },
    ) {
        ExtensionItemContent(
            extension = extension,
            installStep = installStep,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExtensionItemContent(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = MaterialTheme.padding.medium),
    ) {
        Text(
            text = extension.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        // Won't look good but it's not like we can ellipsize overflowing content
        FlowRow(
            modifier = Modifier.secondaryItemAlpha(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                var hasAlreadyShownAnElement by remember { mutableStateOf(false) }
                if (extension is Extension.Installed && extension.lang.isNotEmpty()) {
                    hasAlreadyShownAnElement = true
                    Text(
                        text = LocaleHelper.getSourceDisplayName(extension.lang, LocalContext.current),
                    )
                }

                if (extension.versionName.isNotEmpty()) {
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    hasAlreadyShownAnElement = true
                    Text(
                        text = extension.versionName,
                    )
                }

                val warning = when {
                    extension is Extension.Untrusted -> MR.strings.ext_untrusted
                    extension is Extension.Installed && extension.isObsolete -> MR.strings.ext_obsolete
                    // SY -->
                    extension is Extension.Installed && extension.isRedundant -> SYMR.strings.ext_redundant
                    // SY <--
                    extension.isNsfw -> MR.strings.ext_nsfw_short
                    else -> null
                }
                if (warning != null) {
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    hasAlreadyShownAnElement = true
                    Text(
                        text = stringResource(warning).uppercase(),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (extension is Extension.Installed && !extension.isShared) {
                    if (hasAlreadyShownAnElement) DotSeparatorNoSpaceText()
                    Text(
                        text = stringResource(MR.strings.ext_installer_private),
                    )
                }

                if (!installStep.isCompleted()) {
                    DotSeparatorNoSpaceText()
                    Text(
                        text = when (installStep) {
                            InstallStep.Pending -> stringResource(MR.strings.ext_pending)
                            InstallStep.Downloading -> stringResource(MR.strings.ext_downloading)
                            InstallStep.Installing -> stringResource(MR.strings.ext_installing)
                            else -> error("Must not show non-install process text")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionItemActions(
    extension: Extension,
    installStep: InstallStep,
    modifier: Modifier = Modifier,
    onClickItemCancel: (Extension) -> Unit = {},
    onClickItemAction: (Extension) -> Unit = {},
    onClickItemSecondaryAction: (Extension) -> Unit = {},
) {
    val isIdle = installStep.isCompleted()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        when {
            !isIdle -> {
                IconButton(onClick = { onClickItemCancel(extension) }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                    )
                }
            }
            installStep == InstallStep.Error -> {
                IconButton(onClick = { onClickItemAction(extension) }) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(MR.strings.action_retry),
                    )
                }
            }
            installStep == InstallStep.Idle -> {
                when (extension) {
                    is Extension.Installed -> {
                        IconButton(onClick = { onClickItemSecondaryAction(extension) }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(MR.strings.action_settings),
                            )
                        }

                        if (extension.hasUpdate) {
                            IconButton(onClick = { onClickItemAction(extension) }) {
                                Icon(
                                    imageVector = Icons.Outlined.GetApp,
                                    contentDescription = stringResource(MR.strings.ext_update),
                                )
                            }
                        }
                    }
                    is Extension.Untrusted -> {
                        IconButton(onClick = { onClickItemAction(extension) }) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = stringResource(MR.strings.ext_trust),
                            )
                        }
                    }
                    is Extension.Available -> {
                        if (extension.sources.isNotEmpty()) {
                            IconButton(
                                onClick = { onClickItemSecondaryAction(extension) },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                )
                            }
                        }

                        IconButton(onClick = { onClickItemAction(extension) }) {
                            Icon(
                                imageVector = Icons.Outlined.GetApp,
                                contentDescription = stringResource(MR.strings.ext_install),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionHeader(
    textRes: StringResource,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    ExtensionHeader(
        text = stringResource(textRes),
        modifier = modifier,
        action = action,
    )
}

@Composable
private fun ExtensionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.header,
        )
        action()
    }
}

@Composable
private fun ExtensionTrustDialog(
    onClickConfirm: () -> Unit,
    onClickDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.untrusted_extension))
        },
        text = {
            Text(text = stringResource(MR.strings.untrusted_extension_message))
        },
        confirmButton = {
            TextButton(onClick = onClickConfirm) {
                Text(text = stringResource(MR.strings.ext_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickDismiss) {
                Text(text = stringResource(MR.strings.ext_uninstall))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}
