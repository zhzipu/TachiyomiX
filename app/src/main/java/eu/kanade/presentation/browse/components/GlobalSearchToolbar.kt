package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalSearchToolbar(
    searchQuery: String?,
    progress: Int,
    total: Int,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    hideSourceFilter: Boolean,
    sourceFilter: SourceFilter,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onlyShowHasResults: Boolean,
    onToggleResults: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    searchHistory: List<String> = emptyList(),
    onSearchHistoryClick: (String) -> Unit = {},
    onClearSearchHistory: (() -> Unit)? = null,
    // 多选模式
    selectionMode: Boolean = false,
    selectionCount: Int = 0,
    onToggleSelectionMode: () -> Unit = {},
    onCancelSelectionMode: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onAddSelectionToLibrary: () -> Unit = {},
) {
    if (selectionMode) {
        AppBar(
            titleContent = { AppBarTitle(selectionCount.toString()) },
            isActionMode = true,
            onCancelActionMode = onCancelSelectionMode,
            actions = {
                AppBarActions(
                    listOf(
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
        )
        return
    }

    // 搜索框下方筛选栏的高度，用于把搜索历史下移到筛选栏之下，避免遮挡搜索框
    var chipsHeightPx by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box {
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                onClickCloseSearch = navigateUp,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
                searchHistory = searchHistory,
                onSearchHistoryClick = onSearchHistoryClick,
                searchHistoryOffset = with(LocalDensity.current) { DpOffset(0.dp, chipsHeightPx.toDp()) },
                onClearSearchHistory = onClearSearchHistory,
            )
            if (progress in 1..<total) {
                LinearProgressIndicator(
                    progress = { progress / total.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small)
                .onSizeChanged { chipsHeightPx = it.height },
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            // TODO: make this UX better; it only applies when triggering a new search
            if (!hideSourceFilter) {
                FilterChip(
                    selected = sourceFilter == SourceFilter.PinnedOnly,
                    onClick = { onChangeSearchFilter(SourceFilter.PinnedOnly) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(text = stringResource(MR.strings.pinned_sources))
                    },
                )
                FilterChip(
                    selected = sourceFilter == SourceFilter.All,
                    onClick = { onChangeSearchFilter(SourceFilter.All) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = null,
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(text = stringResource(MR.strings.all))
                    },
                )

                VerticalDivider()
            }

            FilterChip(
                selected = onlyShowHasResults,
                onClick = { onToggleResults() },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(text = stringResource(MR.strings.has_results))
                },
            )

            // 进入多选模式（与图源漫画列表的多选一致）
            FilterChip(
                selected = false,
                onClick = onToggleSelectionMode,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Checklist,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(text = stringResource(MR.strings.action_bulk_select))
                },
            )
        }

        HorizontalDivider()
    }
}
