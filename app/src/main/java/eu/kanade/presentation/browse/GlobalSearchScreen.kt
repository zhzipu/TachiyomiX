package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun GlobalSearchScreen(
    state: SearchScreenModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    onSearchHistoryClick: (String) -> Unit = {},
    onClearSearchHistory: (() -> Unit)? = null,
    // SY -->
    // 多选模式
    onToggleSelectionMode: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onAddSelectionToLibrary: () -> Unit = {},
    // 多选模式下按图源全选/全不选
    onToggleSourceSelection: ((List<Manga>, Boolean) -> Unit)? = null,
    // SY <--
) {
    Scaffold(
        topBar = { scrollBehavior ->
            GlobalSearchToolbar(
                searchQuery = state.searchQuery,
                progress = state.progress,
                total = state.total,
                navigateUp = navigateUp,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                hideSourceFilter = false,
                sourceFilter = state.sourceFilter,
                onChangeSearchFilter = onChangeSearchFilter,
                onlyShowHasResults = state.onlyShowHasResults,
                onToggleResults = onToggleResults,
                scrollBehavior = scrollBehavior,
                searchHistory = state.searchHistory,
                onSearchHistoryClick = onSearchHistoryClick,
                onClearSearchHistory = onClearSearchHistory,
                selectionMode = state.selectionMode,
                selectionCount = state.selection.size,
                onToggleSelectionMode = onToggleSelectionMode,
                onCancelSelectionMode = onToggleSelectionMode,
                onSelectAll = onSelectAll,
                onInvertSelection = onInvertSelection,
                onAddSelectionToLibrary = onAddSelectionToLibrary,
            )
        },
    ) { paddingValues ->
        GlobalSearchContent(
            items = state.filteredItems,
            contentPadding = paddingValues,
            getManga = getManga,
            onClickSource = onClickSource,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
            selection = state.selection,
            onToggleSourceSelection = onToggleSourceSelection.takeIf { state.selectionMode },
        )
    }
}

@Composable
internal fun GlobalSearchContent(
    items: Map<Source, SearchItemResult>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    selection: List<Manga> = emptyList(),
    // 多选模式下按图源全选/全不选
    onToggleSourceSelection: ((List<Manga>, Boolean) -> Unit)? = null,
    fromSourceId: Long? = null,
) {
    val selectedIds = remember(selection) { selection.mapTo(mutableSetOf()) { it.id } }
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, result) ->
            item(key = source.id) {
                val mangas = (result as? SearchItemResult.Success)?.result.orEmpty()
                val allSelected = mangas.isNotEmpty() && mangas.all { it.id in selectedIds }
                GlobalSearchResultItem(
                    title = fromSourceId?.let {
                        "▶ ${source.name}".takeIf { source.id == fromSourceId }
                    } ?: source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                    onToggleSelectAll = onToggleSourceSelection
                        ?.takeIf { mangas.isNotEmpty() }
                        ?.let { toggle -> { toggle(mangas, !allSelected) } },
                    allSelected = allSelected,
                    modifier = Modifier.animateItem(),
                ) {
                    when (result) {
                        SearchItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is SearchItemResult.Success -> {
                            GlobalSearchCardRow(
                                titles = result.result,
                                getManga = getManga,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                                selection = selection,
                            )
                        }
                        is SearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = result.throwable.message)
                        }
                    }
                }
            }
        }
    }
}
