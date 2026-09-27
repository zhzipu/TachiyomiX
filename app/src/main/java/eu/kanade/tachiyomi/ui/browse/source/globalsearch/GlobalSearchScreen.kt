package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.ui.ifSourcesLoaded
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val screenModel = rememberScreenModel {
            GlobalSearchScreenModel(
                initialQuery = searchQuery,
                initialExtensionFilter = extensionFilter,
            )
        }
        val state by screenModel.state.collectAsState()
        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1)
        }
        // 待选择分类的作品，以及实时分类列表（编辑分类后能拿到新分类）
        val categoryChooserMangas by screenModel.categoryChooserMangas.collectAsState()
        val categories by screenModel.categories.collectAsState(initial = emptyList())
        // 跳去编辑分类时本页不在最前，避免对话框浮在编辑分类页面之上
        val isCurrentScreen = navigator.lastItem === this
        // 全选/反选针对当前可见的搜索结果
        val visibleManga = remember(state.filteredItems) {
            state.filteredItems.values
                .filterIsInstance<SearchItemResult.Success>()
                .flatMap { it.result }
        }

        BackHandler(enabled = state.selectionMode) {
            screenModel.toggleSelectionMode()
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.items.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val manga = result.result.singleOrNull()
                        if (manga != null) {
                            navigator.replace(MangaScreen(manga.id, true))
                        } else {
                            // Backoff to result screen
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            GlobalSearchScreen(
                state = state,
                navigateUp = navigator::pop,
                onChangeSearchQuery = screenModel::updateSearchQuery,
                onSearch = { screenModel.search() },
                getManga = { screenModel.getManga(it) },
                onChangeSearchFilter = screenModel::setSourceFilter,
                onToggleResults = screenModel::toggleFilterResults,
                onClickSource = {
                    navigator.push(BrowseSourceScreen(it.id, state.searchQuery))
                },
                onClickItem = { manga ->
                    if (state.selectionMode) {
                        screenModel.toggleSelection(manga)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                // 长按封面：加入书架 / 已在书架则取消收藏（多选模式下为切换选中）
                onLongClickItem = { manga ->
                    when {
                        state.selectionMode -> screenModel.toggleSelection(manga)

                        manga.favorite -> scope.launch {
                            screenModel.removeFromLibrary(manga)
                            context.toast(MR.strings.manga_removed_library)
                        }

                        else -> scope.launch {
                            when (screenModel.addToLibrary(manga)) {
                                AddToLibraryResult.Added -> context.toast(MR.strings.manga_added_library)
                                AddToLibraryResult.AlreadyInLibrary -> context.toast(MR.strings.in_library)
                                AddToLibraryResult.NeedsCategoryChoice -> {
                                    screenModel.showCategoryChooser(listOf(manga))
                                }
                            }
                        }
                    }
                },
                // 多选模式（与图源漫画列表一致）
                onToggleSelectionMode = screenModel::toggleSelectionMode,
                onSelectAll = { screenModel.selectAllVisible(visibleManga) },
                onInvertSelection = { screenModel.invertSelectionVisible(visibleManga) },
                onToggleSourceSelection = screenModel::toggleSourceSelection,
                onAddSelectionToLibrary = {
                    val selection = state.selection
                    scope.launch {
                        when (screenModel.addToLibrary(selection)) {
                            AddToLibraryResult.Added -> {
                                screenModel.toggleSelectionMode()
                                context.toast(MR.strings.manga_added_library)
                            }

                            AddToLibraryResult.AlreadyInLibrary -> {
                                screenModel.toggleSelectionMode()
                                context.toast(MR.strings.in_library)
                            }

                            AddToLibraryResult.NeedsCategoryChoice -> {
                                screenModel.showCategoryChooser(selection)
                            }
                        }
                    }
                },
                onSearchHistoryClick = { query ->
                    screenModel.updateSearchQuery(query)
                    screenModel.search()
                },
                onClearSearchHistory = screenModel::clearSearchHistory,
            )
        }

        // 未设置默认分类时，让用户选择要加入的分类（与图源列表一致）
        // 分类列表是实时的，编辑分类后这里会带上新分类
        categoryChooserMangas?.let { mangas ->
            if (isCurrentScreen) {
                val initialSelection = remember(categories) {
                    categories.mapAsCheckboxState { false }
                }
                key(initialSelection) {
                    ChangeCategoryDialog(
                        initialSelection = initialSelection,
                        onDismissRequest = screenModel::dismissCategoryChooser,
                        onEditCategories = {
                            // 从编辑分类返回后继续显示，且分类列表已刷新
                            screenModel.showCategoryChooser(mangas)
                            navigator.push(CategoryScreen())
                        },
                        onConfirm = { include, _ ->
                            screenModel.dismissCategoryChooser()
                            scope.launch {
                                screenModel.addToLibrary(mangas, include)
                                screenModel.toggleSelectionMode()
                                context.toast(MR.strings.manga_added_library)
                            }
                        },
                    )
                }
            }
        }
    }
}
