package eu.kanade.tachiyomi.ui.browse.source

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import exh.ui.smartsearch.SmartSearchScreen
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.domain.source.service.SourceManager
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.source.network.NetworkSource
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent = sourcesTab(
    smartSearchConfig = smartSearchConfig,
    screenModel = rememberScreenModel { SourcesScreenModel(smartSearchConfig = smartSearchConfig) },
)

@Composable
fun Screen.sourcesTab(
    smartSearchConfig: SmartSearchConfig?,
    screenModel: SourcesScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> MR.strings.label_sources
            false -> SYMR.strings.find_in_another_source
        },
        searchEnabled = true,
        searchQuery = state.searchQuery,
        onChangeSearchQuery = screenModel::search,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen(smartSearchConfig?.origTitle ?: "")) },
            ),
        ).let {
            when (smartSearchConfig) {
                null -> {
                    it.plus(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = { navigator.push(SourcesFilterScreen()) },
                        ),
                    )
                }
                else -> it
            }
        },
        // SY <--
        content = { contentPadding, snackbarHostState ->
            BackHandler(enabled = state.searchQuery != null) {
                screenModel.search(null)
            }

            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    // SY -->
                    // 图源首页：导航（保持原有逻辑）/ 最近更新 / 浏览
                    val homeScreen = when (screenModel.sourceHomePage) {
                        UiPreferences.SourceHomePage.NAVIGATION -> if (screenModel.useNewSourceNavigation) {
                            SourceFeedScreen(source.id)
                        } else {
                            BrowseSourceScreen(source.id, Listing.Popular.query)
                        }
                        // 图源不支持最近更新时回退到浏览列表
                        UiPreferences.SourceHomePage.LATEST -> if (source.supportsLatest) {
                            BrowseSourceScreen(source.id, Listing.Latest.query)
                        } else {
                            BrowseSourceScreen(source.id, Listing.Popular.query)
                        }
                        UiPreferences.SourceHomePage.BROWSE -> BrowseSourceScreen(source.id, Listing.Popular.query)
                    }
                    val screen = when {
                        smartSearchConfig != null -> SmartSearchScreen(source.id, smartSearchConfig)
                        // 图源列表里点「最近更新」按钮时始终进入最近更新
                        listing == Listing.Latest -> BrowseSourceScreen(source.id, listing.query)
                        else -> homeScreen
                    }
                    // 网络图源还没配置好（服务器地址没填 / 填的不合法）时，进去也只会看到
                    // 「没有结果」加一个「修改插件设置」按钮 —— 直接落到设置页，少点一步。
                    val networkSourceReady = (Injekt.get<SourceManager>().get(NetworkSource.ID) as? NetworkSource)
                        ?.isConfigured() == true
                    if (source.id == NetworkSource.ID && !networkSourceReady) {
                        navigator.push(SourcePreferencesScreen(source.id))
                    } else {
                        navigator.push(screen)
                    }
                    // SY <--
                },
                onClickPin = screenModel::togglePin,
                onLongClickItem = screenModel::showSourceDialog,
                // SY -->
                installedLanguages = state.installedLanguages.takeIf { smartSearchConfig == null }.orEmpty(),
                nsfwFilter = state.nsfwFilter.takeIf { smartSearchConfig == null },
                onNsfwFilterClick = screenModel::toggleNsfwFilter,
                onMoveLanguage = screenModel::moveLanguage,
                // SY <--
            )

            when (val dialog = state.dialog) {
                is SourcesScreenModel.Dialog.SourceLongClick -> {
                    val source = dialog.source
                    SourceOptionsDialog(
                        source = source,
                        onClickPin = {
                            screenModel.togglePin(source)
                            screenModel.closeDialog()
                        },
                        onClickDisable = {
                            screenModel.toggleSource(source)
                            screenModel.closeDialog()
                        },
                        // SY -->
                        onClickSetCategories = {
                            screenModel.showSourceCategoriesDialog(source)
                        }.takeIf { state.categories.isNotEmpty() },
                        onClickToggleDataSaver = {
                            screenModel.toggleExcludeFromDataSaver(source)
                            screenModel.closeDialog()
                        }.takeIf { state.dataSaverEnabled },
                        onDismiss = screenModel::closeDialog,
                    )
                }
                is SourcesScreenModel.Dialog.SourceCategories -> {
                    val source = dialog.source
                    SourceCategoriesDialog(
                        source = source,
                        categories = state.categories,
                        onClickCategories = { categories ->
                            screenModel.setSourceCategories(source, categories)
                            screenModel.closeDialog()
                        },
                        onDismissRequest = screenModel::closeDialog,
                    )
                }
                null -> Unit
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
