package eu.kanade.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.showSoftKeyboard

@Composable
fun AppBar(
    title: String?,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    // Text
    subtitle: String? = null,
    // Up button
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    // SY --> 左上角的自定义内容（放在 up 按钮的位置）。为 null 时行为不变。
    navigationContent: @Composable (() -> Unit)? = null,
    // SY <--
    // Menu
    actions: @Composable RowScope.() -> Unit = {},
    // Action mode
    actionModeCounter: Int = 0,
    onCancelActionMode: () -> Unit = {},
    actionModeActions: @Composable RowScope.() -> Unit = {},

    scrollBehavior: TopAppBarScrollBehavior? = null,
    // 点击标题时触发（例如让列表回到顶部）
    onClickTitle: (() -> Unit)? = null,
) {
    val isActionMode by remember(actionModeCounter) {
        derivedStateOf { actionModeCounter > 0 }
    }

    AppBar(
        modifier = modifier,
        backgroundColor = backgroundColor,
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(
                    title = title,
                    modifier = if (onClickTitle != null) {
                        Modifier.clickable(onClick = onClickTitle)
                    } else {
                        Modifier
                    },
                    subtitle = subtitle,
                )
            }
        },
        navigateUp = navigateUp,
        navigationIcon = navigationIcon,
        // SY -->
        navigationContent = navigationContent,
        // SY <--
        actions = {
            if (isActionMode) {
                actionModeActions()
            } else {
                actions()
            }
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun AppBar(
    // Title
    titleContent: @Composable () -> Unit,

    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    // Up button
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    // SY --> 左上角的自定义内容（放在 up 按钮的位置）。为 null 时行为不变。
    navigationContent: @Composable (() -> Unit)? = null,
    // SY <--
    // Menu
    actions: @Composable RowScope.() -> Unit = {},
    // Action mode
    isActionMode: Boolean = false,
    onCancelActionMode: () -> Unit = {},

    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    Column(
        modifier = modifier,
    ) {
        TopAppBar(
            navigationIcon = {
                if (isActionMode) {
                    IconButton(onClick = onCancelActionMode) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_cancel),
                        )
                    }
                } else if (navigationContent != null) {
                    // SY --> 自定义左上角内容优先于标准的返回箭头
                    navigationContent()
                    // SY <--
                } else {
                    navigateUp?.let {
                        IconButton(onClick = it) {
                            UpIcon(navigationIcon = navigationIcon)
                        }
                    }
                }
            },
            title = titleContent,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceColorAtElevation(
                    elevation = if (isActionMode) 3.dp else 0.dp,
                ),
            ),
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
fun AppBarTitle(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        title?.let {
            Text(
                text = it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(
                    repeatDelayMillis = 2_000,
                ),
            )
        }
    }
}

@Composable
fun AppBarActions(
    actions: List<AppBar.AppBarAction>,
) {
    var showMenu by remember { mutableStateOf(false) }

    actions.filterNot { it is AppBar.OverflowAction }.forEach { action ->
        when (action) {
            is AppBar.Action -> AppBarActionButton(
                title = action.title,
                enabled = action.enabled,
                onClick = action.onClick,
            ) {
                Icon(
                    imageVector = action.icon,
                    tint = action.iconTint ?: LocalContentColor.current,
                    contentDescription = action.title,
                )
            }
            // 位图图标（如用户自定义图标），按主题色着色
            is AppBar.PainterAction -> AppBarActionButton(
                title = action.title,
                enabled = action.enabled,
                onClick = action.onClick,
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = action.title,
                    modifier = Modifier.size(24.dp),
                    tint = action.iconTint ?: LocalContentColor.current,
                )
            }
            is AppBar.OverflowAction -> Unit
        }
    }

    val overflowActions = actions.filterIsInstance<AppBar.OverflowAction>()
    if (overflowActions.isNotEmpty()) {
        TooltipBox(
            positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip {
                    Text(stringResource(MR.strings.action_menu_overflow_description))
                }
            },
            state = rememberTooltipState(),
            focusable = false,
        ) {
            IconButton(
                onClick = { showMenu = !showMenu },
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            overflowActions.map {
                DropdownMenuItem(
                    onClick = {
                        it.onClick()
                        showMenu = false
                    },
                    text = { Text(it.title, fontWeight = FontWeight.Normal) },
                )
            }
        }
    }
}

@Composable
private fun AppBarActionButton(
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(title)
            }
        },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            content()
        }
    }
}

/**
 * 搜索历史下拉。
 *
 * 位置直接由搜索栏在窗口中的位置算出（固定在搜索栏正下方），不依赖 Material3
 * DropdownMenu 的锚点推算，避免历史列表压住搜索框。
 */
@Composable
private fun SearchHistoryDropdown(
    barBounds: IntRect,
    offset: DpOffset,
    history: List<String>,
    onDismissRequest: () -> Unit,
    onSearchHistoryClick: (String) -> Unit,
    onSearchHistoryAddToTag: ((String) -> Unit)?,
    onClearSearchHistory: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    // IME（软键盘）insets 对象必须在 Popup 之外取：Popup 的内容运行在独立子组合/子窗口里，
    // 在那边调用 WindowInsets.ime 拿到的是弹窗自己窗口的 inset，恒为 0。
    // 真正的「读取高度」动作放在 Popup 内部，这样键盘弹出/收起能直接触发弹窗重组。
    val imeInsets = WindowInsets.ime
    val positionProvider = remember(barBounds, offset, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = with(density) { offset.x.roundToPx() }
                val y = barBounds.bottom + with(density) { offset.y.roundToPx() }
                return IntOffset(x, y.coerceAtLeast(0))
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false),
    ) {
        // 列表底部不得越过键盘顶部，否则会被输入法盖住，最后几条历史与「清除搜索历史」都点不到。
        // 若窗口已被 IME resize，imeBottom 本身为 0（此时 screenHeightDp 已不含键盘区域），不会重复扣减。
        val barBottom = with(density) { barBounds.bottom.toDp() }
        val imeBottom = with(density) { imeInsets.getBottom(density).toDp() }
        val maxHeight = (screenHeightDp - barBottom - offset.y - imeBottom - 16.dp)
            .coerceAtLeast(96.dp)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(
                modifier = Modifier.heightIn(max = maxHeight),
            ) {
                // 只有历史条目参与滚动；「清除搜索历史」固定在菜单最下方，列表再长也不会被滚走。
                // weight 需要「有界」的主轴约束才会生效，外层 heightIn(max = maxHeight) 正好提供：
                // size 修饰符会先把 Popup 传来的约束夹到 maxHeight，所以 Column 收到的 maxHeight 一定是有限值。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    history.forEach { query ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = query,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = { onSearchHistoryClick(query) },
                            trailingIcon = onSearchHistoryAddToTag?.let { addToTag ->
                                {
                                    IconButton(onClick = { addToTag(query) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = stringResource(SYMR.strings.add_to_tag),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (onClearSearchHistory != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.clear_search_history)) },
                        onClick = onClearSearchHistory,
                    )
                }
            }
        }
    }
}

/**
 * @param searchEnabled Set to false if you don't want to show search action.
 * @param searchQuery If null, use normal toolbar.
 * @param placeholderText If null, [MR.strings.action_search_hint] is used.
 */
@Composable
fun SearchToolbar(
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    modifier: Modifier = Modifier,
    titleContent: @Composable () -> Unit = {},
    navigateUp: (() -> Unit)? = null,
    // SY --> 左上角的自定义内容（只在非搜索态显示）。为 null 时行为不变。
    navigationContent: @Composable (() -> Unit)? = null,
    // SY <--
    searchEnabled: Boolean = true,
    placeholderText: String? = null,
    onSearch: (String) -> Unit = {},
    onClickCloseSearch: () -> Unit = { onChangeSearchQuery(null) },
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    searchHistory: List<String> = emptyList(),
    onSearchHistoryClick: (String) -> Unit = {},
    // 搜索历史下拉的额外垂直偏移，用于避开搜索框下方的其它控件（如筛选栏）
    searchHistoryOffset: DpOffset = DpOffset.Zero,
    onSearchHistoryAddToTag: ((String) -> Unit)? = null,
    onClearSearchHistory: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    // 搜索栏在窗口中的位置，用于把搜索历史固定显示在搜索栏正下方
    var barBounds by remember { mutableStateOf(IntRect.Zero) }

    AppBar(
        modifier = modifier.onGloballyPositioned { barBounds = it.boundsInWindow().roundToIntRect() },
        titleContent = {
            if (searchQuery == null) return@AppBar titleContent()

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            val searchAndClearFocus: () -> Unit = f@{
                if (searchQuery.isBlank()) return@f
                onSearch(searchQuery)
                focusManager.clearFocus()
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Next)
            }

            var showSearchHistory by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onChangeSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .runOnEnterKeyPressed(action = searchAndClearFocus)
                        .onFocusChanged { showSearchHistory = it.isFocused }
                        .showSoftKeyboard(remember { searchQuery.isEmpty() })
                        .clearFocusOnSoftKeyboardHide(),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchAndClearFocus() }),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = searchQuery,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = visualTransformation,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(
                                    modifier = Modifier.secondaryItemAlpha(),
                                    text = (placeholderText ?: stringResource(MR.strings.action_search_hint)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                )
                            },
                            container = {},
                        )
                    },
                )

                if (showSearchHistory && searchQuery.isEmpty() && searchHistory.isNotEmpty()) {
                    SearchHistoryDropdown(
                        barBounds = barBounds,
                        offset = searchHistoryOffset,
                        history = searchHistory,
                        onDismissRequest = { showSearchHistory = false },
                        onSearchHistoryClick = { query ->
                            showSearchHistory = false
                            onSearchHistoryClick(query)
                        },
                        onSearchHistoryAddToTag = onSearchHistoryAddToTag?.let { addToTag ->
                            { query ->
                                showSearchHistory = false
                                addToTag(query)
                            }
                        },
                        onClearSearchHistory = onClearSearchHistory?.let { onClear ->
                            {
                                showSearchHistory = false
                                onClear()
                            }
                        },
                    )
                }
            }
        },
        navigateUp = if (searchQuery == null) navigateUp else onClickCloseSearch,
        // SY -->
        // 搜索态下左上角必须是「关闭搜索」，所以自定义内容只在非搜索态生效 ——
        // 否则用户进了搜索就退不出来了。
        navigationContent = navigationContent.takeIf { searchQuery == null },
        // SY <--
        actions = {
            key("search") {
                val onClick = { onChangeSearchQuery("") }

                if (!searchEnabled) {
                    // Don't show search action
                } else if (searchQuery == null) {
                    TooltipBox(
                        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(MR.strings.action_search))
                            }
                        },
                        state = rememberTooltipState(),
                        focusable = false,
                    ) {
                        IconButton(
                            onClick = onClick,
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(MR.strings.action_search),
                            )
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    TooltipBox(
                        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(MR.strings.action_reset))
                            }
                        },
                        state = rememberTooltipState(),
                        focusable = false,
                    ) {
                        IconButton(
                            onClick = {
                                onClick()
                                focusRequester.requestFocus()
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_reset),
                            )
                        }
                    }
                }
            }

            key("actions") { actions() }
        },
        isActionMode = false,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun UpIcon(
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
) {
    val icon = navigationIcon
        ?: Icons.AutoMirrored.Outlined.ArrowBack
    Icon(
        imageVector = icon,
        contentDescription = stringResource(MR.strings.action_bar_up_description),
        modifier = modifier,
    )
}

sealed interface AppBar {
    sealed interface AppBarAction

    data class Action(
        val title: String,
        val icon: ImageVector,
        val iconTint: Color? = null,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : AppBarAction

    /** 使用位图资源作为图标的操作，例如用户自定义的图标 */
    data class PainterAction(
        val title: String,
        @DrawableRes val iconRes: Int,
        val iconTint: Color? = null,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : AppBarAction

    data class OverflowAction(
        val title: String,
        val onClick: () -> Unit,
    ) : AppBarAction
}
