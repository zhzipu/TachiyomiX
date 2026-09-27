package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.R
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.RankedSearchMetadata
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    contentPadding: PaddingValues,
    // SY -->
    selection: List<Manga> = emptyList(),
    // SY <--
    state: LazyListState,
    // SY -->
    // 递增时通知当前页面的列表回到顶部
    scrollToTopNonce: Int = 0,
    isCurrentPage: Boolean = true,
    // SY <--
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    // SY -->
    // 点击顶部图源名称时，列表带动画回到顶部
    LaunchedEffect(scrollToTopNonce) {
        if (scrollToTopNonce > 0 && isCurrentPage) {
            state.animateScrollToItem(0)
        }
    }
    // SY <--
    LazyColumn(
        state = state,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            // SY -->
            val pair by mangaList[index]?.collectAsState() ?: return@items
            val manga = pair.first
            val metadata = pair.second
            val isSelected = selection.any { it.id == manga.id }
            // SY <--

            BrowseSourceListItem(
                manga = manga,
                // SY -->
                metadata = metadata,
                isSelected = isSelected,
                // SY <--
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    // SY -->
    metadata: RaisedSearchMetadata?,
    isSelected: Boolean = false,
    // SY <--
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    MangaListItem(
        isSelected = isSelected,
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = {
            InLibraryBadge(enabled = manga.favorite)
            // SY -->
            if (metadata is MangaDexSearchMetadata) {
                metadata.followStatus?.let { followStatus ->
                    val text = LocalContext.current
                        .resources
                        .let {
                            remember {
                                it.getStringArray(R.array.md_follows_options)
                                    .getOrNull(followStatus)
                            }
                        }
                        ?: return@let
                    Badge(
                        text = text,
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
                metadata.relation?.let {
                    Badge(
                        text = stringResource(it.res),
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            } else if (metadata is RankedSearchMetadata) {
                metadata.rank?.let {
                    Badge(
                        text = "+$it",
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
            // SY <--
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
