package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.zIndex
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TabbedScreen(
    titleRes: StringResource,
    tabs: List<TabContent>,
    state: PagerState = rememberPagerState { tabs.size },
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 再次点击当前标签时递增，通知该标签页的列表回到顶部
    var scrollToTopNonce by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            val tab = tabs[state.currentPage]

            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(titleRes)) },
                searchEnabled = tab.searchEnabled,
                searchQuery = tab.searchQuery,
                onChangeSearchQuery = tab.onChangeSearchQuery,
                actions = { AppBarActions(tab.actions) },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier.padding(
                top = contentPadding.calculateTopPadding(),
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
            ),
        ) {
            PrimaryTabRow(
                selectedTabIndex = state.currentPage,
                modifier = Modifier.zIndex(1f),
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = state.currentPage == index,
                        onClick = {
                            if (state.currentPage == index) {
                                // 已在该标签，再次点击让列表回到顶部
                                scrollToTopNonce++
                            } else {
                                scope.launch { state.animateScrollToPage(index) }
                            }
                        },
                        text = { TabText(text = stringResource(tab.titleRes), badgeCount = tab.badgeNumber) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = state,
                verticalAlignment = Alignment.Top,
            ) { page ->
                CompositionLocalProvider(
                    LocalTabScrollToTopState provides TabScrollToTopState(
                        nonce = scrollToTopNonce,
                        isCurrentPage = page == state.currentPage,
                    ),
                ) {
                    tabs[page].content(
                        PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                        snackbarHostState,
                    )
                }
            }
        }
    }
}

data class TabContent(
    val titleRes: StringResource,
    val badgeNumber: Int? = null,
    val searchEnabled: Boolean = false,
    val searchQuery: String? = null,
    val onChangeSearchQuery: (String?) -> Unit = {},
    val actions: List<AppBar.AppBarAction> = emptyList(),
    val content: @Composable (contentPadding: PaddingValues, snackbarHostState: SnackbarHostState) -> Unit,
)

/**
 * 顶部标签页的"回到顶部"触发状态。
 *
 * [nonce] 递增表示用户再次点击了当前标签；[isCurrentPage] 标记该内容是否处于当前标签，
 * 只有当前标签的列表才响应，预加载的相邻标签不会误滚动。
 */
@Immutable
data class TabScrollToTopState(
    val nonce: Int = 0,
    val isCurrentPage: Boolean = false,
)

val LocalTabScrollToTopState = compositionLocalOf { TabScrollToTopState() }
