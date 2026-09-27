package eu.kanade.presentation.browse.components

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import exh.source.anyIs
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    // SY -->
    // 传 null 表示这个图源不需要 WebView 入口（例如网络图源：它的「主页」是 WebDAV 根目录，
    // 用 WebView 打开没有意义），此时工具栏和溢出菜单里都不会出现这一项。
    onWebViewClick: (() -> Unit)?,
    // SY <--
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    // SY -->
    // 点击图源名称时触发（用于让漫画列表回到顶部）
    onClickSourceTitle: () -> Unit = {},
    // SY <--
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    searchHistory: List<String> = emptyList(),
    onSearchHistoryClick: (String) -> Unit = {},
    onSearchHistoryAddToTag: ((String) -> Unit)? = null,
    onClearSearchHistory: (() -> Unit)? = null,
    // SY -->
    selectionMode: Boolean = false,
    selectionCount: Int = 0,
    onToggleSelectionMode: () -> Unit = {},
    onCancelSelectionMode: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onAddSelectionToLibrary: () -> Unit = {},
    // SY <--
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source?.anyIs<ConfigurableSource>() == true

    if (selectionMode) {
        // Multi-select action mode bar.
        AppBar(
            titleContent = { AppBarTitle(selectionCount.toString()) },
            isActionMode = true,
            onCancelActionMode = onCancelSelectionMode,
            actions = {
                AppBarActions(
                    actions = listOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_all),
                            icon = Icons.Outlined.SelectAll,
                            onClick = onSelectAll,
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_inverse),
                            icon = Icons.Outlined.FlipToBack,
                            onClick = onInvertSelection,
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.add_to_library),
                            icon = Icons.Outlined.FavoriteBorder,
                            enabled = selectionCount > 0,
                            onClick = onAddSelectionToLibrary,
                        ),
                    ),
                )
            },
            scrollBehavior = scrollBehavior,
        )
        return
    }

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = {
            // SY -->
            // 点击图源名称让漫画列表回到顶部
            AppBarTitle(
                title = title,
                modifier = Modifier.clickable(onClick = onClickSourceTitle),
            )
            // SY <--
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = buildList {
                    // SY -->
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_bulk_select),
                            icon = Icons.Outlined.Checklist,
                            onClick = onToggleSelectionMode,
                        ),
                    )
                    // SY <--
                    if (displayMode != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (displayMode == LibraryDisplayMode.List) {
                                    Icons.AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                    }
                    if (isLocalSource) {
                        if (isConfigurableSource && displayMode != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                ),
                            )
                        } else {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.label_help),
                                    icon = Icons.AutoMirrored.Outlined.Help,
                                    onClick = onHelpClick,
                                ),
                            )
                        }
                    } else {
                        // 只有在提供了回调时才加 WebView 入口（见 onWebViewClick 的注释）
                        if (onWebViewClick != null) {
                            if (isConfigurableSource && displayMode != null) {
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_web_view),
                                        onClick = onWebViewClick,
                                    ),
                                )
                            } else {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_web_view),
                                        icon = Icons.Outlined.Public,
                                        onClick = onWebViewClick,
                                    ),
                                )
                            }
                        }
                    }
                    // SY <--
                    if (isConfigurableSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
        scrollBehavior = scrollBehavior,
        searchHistory = searchHistory,
        onSearchHistoryClick = onSearchHistoryClick,
        onSearchHistoryAddToTag = onSearchHistoryAddToTag,
        onClearSearchHistory = onClearSearchHistory,
    )
}
