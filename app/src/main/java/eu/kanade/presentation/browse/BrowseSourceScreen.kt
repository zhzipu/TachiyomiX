package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceEHentaiList
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.isEhBasedSource
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    columns: GridCells,
    // SY -->
    ehentaiBrowseDisplayMode: Boolean,
    // SY <--
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    // SY -->
    onWebViewClick: (() -> Unit)?,
    onHelpClick: (() -> Unit)?,
    onLocalSourceHelpClick: (() -> Unit)?,
    // 「修改插件设置」：给不需要 WebView 的图源（网络图源）用，
    // 顶掉空列表里那个「在 WebView 中打开」的操作，直接打开图源设置。
    onSourceSettingsClick: (() -> Unit)? = null,
    // SY <--
    listState: LazyListState,
    gridState: LazyGridState,
    // SY -->
    // 递增时通知当前页面的列表回到顶部
    scrollToTopNonce: Int = 0,
    isCurrentPage: Boolean = true,
    // SY <--
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // SY -->
    selection: List<Manga> = emptyList(),
    skipInitialLoading: Boolean = false,
    // SY <--
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        // SY -->
        // Swiping onto an already-preloaded page avoids the flash of the loading spinner: the
        // content is served straight from the cache and appears on the next frame.
        if (!skipInitialLoading) {
            LoadingScreen(Modifier.padding(contentPadding))
        }
        // SY <--
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource /* SY --> */ && onLocalSourceHelpClick != null /* SY <-- */) {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOfNotNull(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = mangaList::refresh,
                    ),
                    // SY -->
                    if (onWebViewClick != null) {
                        EmptyScreenAction(
                            stringRes = MR.strings.action_open_in_web_view,
                            icon = Icons.Outlined.Public,
                            onClick = onWebViewClick,
                        )
                    } else if (onSourceSettingsClick != null) {
                        // 不需要 WebView 的图源（网络图源）把这一项换成「修改插件设置」
                        EmptyScreenAction(
                            stringRes = SYMR.strings.action_modify_source_settings,
                            icon = Icons.Outlined.Settings,
                            onClick = onSourceSettingsClick,
                        )
                    } else {
                        null
                    },
                    if (onHelpClick != null) {
                        EmptyScreenAction(
                            stringRes = MR.strings.label_help,
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            onClick = onHelpClick,
                        )
                    } else {
                        null
                    },
                    // SY <--
                )
            },
        )

        return
    }

    // SY -->
    if (source?.isEhBasedSource() == true && ehentaiBrowseDisplayMode) {
        BrowseSourceEHentaiList(
            mangaList = mangaList,
            contentPadding = contentPadding,
            selection = selection,
            state = listState,
            scrollToTopNonce = scrollToTopNonce,
            isCurrentPage = isCurrentPage,
            onMangaClick = onMangaClick,
            onMangaLongClick = onMangaLongClick,
        )
        return
    }
    // SY <--

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                state = gridState,
                scrollToTopNonce = scrollToTopNonce,
                isCurrentPage = isCurrentPage,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                contentPadding = contentPadding,
                selection = selection,
                state = listState,
                scrollToTopNonce = scrollToTopNonce,
                isCurrentPage = isCurrentPage,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                state = gridState,
                scrollToTopNonce = scrollToTopNonce,
                isCurrentPage = isCurrentPage,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
