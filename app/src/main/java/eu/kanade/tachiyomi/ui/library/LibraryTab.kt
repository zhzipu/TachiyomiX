package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteDownloadsDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.ContinueAllActionRow
import eu.kanade.presentation.library.components.ContinueStage
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryDownloadControls
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.SyncFavoritesConfirmDialog
import eu.kanade.presentation.library.components.SyncFavoritesProgressDialog
import eu.kanade.presentation.library.components.SyncFavoritesWarningDialog
import eu.kanade.presentation.library.components.UploadConflictDialog
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.upload.UploadChoice
import eu.kanade.tachiyomi.data.upload.isDownloadCategory
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import exh.favorites.FavoritesSyncStatus
import exh.recs.RecommendsScreen
import exh.recs.batch.RecommendationSearchBottomSheetDialog
import exh.recs.batch.RecommendationSearchProgressDialog
import exh.recs.batch.SearchStatus
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        // SY -->
        // 「下载」分类顶栏左上角的「继续 / 暂停」。
        //
        // 按钮必须画在顶栏里，而「现在是不是『下载』分类」只有 `LibraryContent`
        // 知道（它拿着 pagerState），所以这个值由那边回传上来。
        var showDownloadControls by remember { mutableStateOf(false) }
        // 「继续」弹窗：第一层选操作对象，第二层（选了「上传」才进）选上传范围
        var showContinueDialog by remember { mutableStateOf(false) }
        var continueStage by remember { mutableStateOf(ContinueStage.PICK_TARGET) }
        var showPauseDialog by remember { mutableStateOf(false) }

        /** 选「上传」时把已下载完的漫画排进上传队列（按本地内容重新扫一遍）。 */
        val enqueueAllForUpload: () -> Unit = {
            val count = screenModel.enqueueAllDownloadedForUpload()
            val message = if (count > 0) {
                context.stringResource(SYMR.strings.upload_manga_queued, count)
            } else {
                context.stringResource(SYMR.strings.download_continue_all_nothing)
            }
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
        // SY <--

        val onClickRefresh: (Category?) -> Boolean = { category ->
            // SY -->
            val started = LibraryUpdateJob.startNow(
                context = context,
                category = if (state.groupType == LibraryGroup.BY_DEFAULT) category else null,
                group = state.groupType,
                groupExtra = when (state.groupType) {
                    LibraryGroup.BY_DEFAULT -> null
                    LibraryGroup.BY_SOURCE, LibraryGroup.BY_TRACK_STATUS -> category?.id?.toString()
                    LibraryGroup.BY_STATUS -> category?.id?.minus(1)?.toString()
                    else -> null
                },
            )
            // SY <--
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = stringResource(MR.strings.label_library),
                    defaultCategoryTitle = stringResource(MR.strings.label_default),
                    page = state.coercedActiveCategoryIndex,
                )
                LibraryToolbar(
                    hasActiveFilters = state.hasActiveFilters,
                    selectedCount = state.selection.size,
                    title = title,
                    onClickUnselectAll = screenModel::clearSelection,
                    onClickSelectAll = screenModel::selectAll,
                    onClickInvertSelection = screenModel::invertSelection,
                    onClickFilter = screenModel::showSettingsDialog,
                    onClickRefresh = { onClickRefresh(state.activeCategory) },
                    onClickGlobalUpdate = { onClickRefresh(null) },
                    onClickOpenRandomManga = {
                        scope.launch {
                            val randomItem = screenModel.getRandomLibraryItem()
                            if (randomItem != null) {
                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                            } else {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(MR.strings.information_no_entries_found),
                                )
                            }
                        }
                    },
                    onClickSyncNow = {
                        if (!SyncDataJob.isRunning(context)) {
                            SyncDataJob.startNow(context, manual = true)
                        } else {
                            context.toast(SYMR.strings.sync_in_progress)
                        }
                    },
                    // SY -->
                    onClickSyncExh = screenModel::openFavoritesSyncDialog.takeIf { state.showSyncExh },
                    isSyncEnabled = state.isSyncEnabled,
                    // 「下载」分类里，左上角放「继续 / 暂停」两个图标按钮。
                    // 其余分类传 null，左上角保持空白（书架是顶层页签，本来就没有返回箭头）。
                    navigationContent = if (showDownloadControls) {
                        {
                            LibraryDownloadControls(
                                onContinue = {
                                    continueStage = ContinueStage.PICK_TARGET
                                    showContinueDialog = true
                                },
                                onPause = { showPauseDialog = true },
                            )
                        }
                    } else {
                        null
                    },
                    // SY <--
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    // For scroll overlay when no tab
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
            },
            bottomBar = {
                // SY -->
                // 底部那个按钮按当前分类切换：书架「下载」分类里是「上传」，
                // 其余分类保持原来的「下载」下拉菜单。
                // 没有分类页签时（例如全局搜索）`activeCategory` 为 null，按「非下载分类」处理。
                val inDownloadCategory = state.activeCategory?.isDownloadCategory == true
                // SY <--
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { screenModel.markReadSelection(true) },
                    onMarkAsUnreadClicked = { screenModel.markReadSelection(false) },
                    // SY -->
                    onDownloadClicked = screenModel::performDownloadAction
                        .takeIf { !inDownloadCategory && state.selectedManga.fastAll { !it.isLocal() } },
                    onUploadClicked = screenModel::performUploadAction
                        .takeIf { inDownloadCategory },
                    // 网络图源没填服务器信息时，上传按钮置灰（点了也没有意义）；
                    // 另外所选漫画里**至少要有一本存在已下载完成的章节**，否则同样没东西可传。
                    uploadEnabled = state.isUploadAvailable &&
                        state.selectedManga.fastAny { screenModel.hasDownloadedChapters(it) },
                    // SY <--
                    onDeleteClicked = screenModel::openDeleteMangaDialog,
                    onMigrateClicked = {
                        val selection = state.selectedManga
                            // SY -->
                            .filterNot { it.source == MERGED_SOURCE_ID }
                            .map { it.id }
                        // <-- SY
                        screenModel.clearSelection()
                        /* SY --> */if (selection.isNotEmpty()) {
                            /* <-- SY */
                            navigator.push(MigrationConfigScreen(selection))
                            // SY ->>
                        } else {
                            context.toast(SYMR.strings.no_valid_entry)
                        }
                        // <-- SY
                    },
                    // SY -->
                    onClickCleanTitles = screenModel::cleanTitles.takeIf { state.showCleanTitles },
                    onClickCollectRecommendations = screenModel::showRecommendationSearchDialog.takeIf { state.selection.size > 1 },
                    onClickAddToMangaDex = screenModel::syncMangaToDex.takeIf { state.showAddToMangadex },
                    onClickResetInfo = screenModel::resetInfo.takeIf { state.showResetInfo },
                    // SY <--
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }

                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }

                else -> {
                    LibraryContent(
                        categories = state.displayedCategories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = state.coercedActiveCategoryIndex,
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = screenModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = screenModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            screenModel.toggleRangeSelection(category, manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = { onClickRefresh(state.activeCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { screenModel.getDisplayMode() },
                        getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                        getItemsForCategory = { state.getItemsForCategory(it) },
                        // SY -->
                        // 书架「下载」分类的进度条数据（其它分类不会用到）
                        getMangaProgress = { state.progressFor(it) },
                        // 「继续 / 暂停」按钮画在顶栏（见上面的 navigationContent），
                        // 这里只开关 + 回传「当前页是不是『下载』分类」
                        showDownloadControls = true,
                        onDownloadControlsVisibleChange = { showDownloadControls = it },
                        // SY <--
                    )
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = state.activeCategory,
                    // SY -->
                    hasCategories = state.libraryData.categories.fastAny { !it.isSystemCategory },
                    // SY <--
                )
            }

            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }

            is LibraryScreenModel.Dialog.DeleteManga -> {
                // SY -->
                if (dialog.downloadsOnly) {
                    // 「下载」分类里：只清本地下载、把这本漫画移出「下载」，书架归属不动，
                    // 所以没有勾选项可问，二次确认一次即可。
                    DeleteDownloadsDialog(
                        onDismissRequest = onDismissRequest,
                        onConfirm = {
                            screenModel.removeDownloadedMangas(dialog.manga)
                            screenModel.clearSelection()
                        },
                    )
                } else {
                    // SY <--
                    DeleteLibraryMangaDialog(
                        containsLocalManga = dialog.manga.any(Manga::isLocal),
                        onDismissRequest = onDismissRequest,
                        onConfirm = { deleteManga, deleteChapter ->
                            screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                            screenModel.clearSelection()
                        },
                    )
                    // SY -->
                }
                // SY <--
            }
            // SY -->
            LibraryScreenModel.Dialog.SyncFavoritesWarning -> {
                SyncFavoritesWarningDialog(
                    onDismissRequest = onDismissRequest,
                    onAccept = {
                        onDismissRequest()
                        screenModel.onAcceptSyncWarning()
                    },
                )
            }

            LibraryScreenModel.Dialog.SyncFavoritesConfirm -> {
                SyncFavoritesConfirmDialog(
                    onDismissRequest = onDismissRequest,
                    onAccept = {
                        onDismissRequest()
                        screenModel.runSync()
                    },
                )
            }

            is LibraryScreenModel.Dialog.RecommendationSearchSheet -> {
                RecommendationSearchBottomSheetDialog(
                    onDismissRequest = onDismissRequest,
                    onSearchRequest = {
                        onDismissRequest()
                        screenModel.clearSelection()
                        screenModel.runRecommendationSearch(dialog.manga)
                    },
                )
            }
            // SY <--
            null -> {}
        }

        // SY -->
        SyncFavoritesProgressDialog(
            status = screenModel.favoritesSync.status.collectAsState().value,
            setStatusIdle = { screenModel.favoritesSync.status.value = FavoritesSyncStatus.Idle },
            openManga = { navigator.push(MangaScreen(it)) },
        )

        RecommendationSearchProgressDialog(
            status = screenModel.recommendationSearch.status.collectAsState().value,
            setStatusIdle = { screenModel.recommendationSearch.status.value = SearchStatus.Idle },
            setStatusCancelling = { screenModel.recommendationSearch.status.value = SearchStatus.Cancelling },
        )

        // 手动上传遇到「服务器上已有同名漫画」时弹窗等用户选合并还是新建。
        // 这时 UploadManager 正挂起等答案（见 UploadManager.askConflict）。
        val pendingUpload by screenModel.pendingUploadDecision.collectAsState()
        pendingUpload?.let { decision ->
            UploadConflictDialog(
                decision = decision,
                onMerge = { screenModel.resolveUploadDecision(UploadChoice.MERGE) },
                onNewFolder = { screenModel.resolveUploadDecision(UploadChoice.NEW_FOLDER) },
                onDismissRequest = { screenModel.resolveUploadDecision(UploadChoice.CANCEL) },
            )
        }

        // 「下载」分类顶栏那个「继续」按钮的弹窗。
        //
        // 两层：
        // 1. 选操作对象 —— 下载 / 上传 / 下载和上传（点弹窗外部或取消按钮退出）
        // 2. 选了「上传」之后再选范围 —— **继续之前的任务**（接着上次退出时留下的
        //    上传队列跑，不再重新扫描本地）还是**全部章节**（按本地已下载的内容
        //    重新排一遍，等价于新开一批上传）
        //
        // 上传那两项只在网络图源配置好了时给点：没配服务器点了只会收到失败通知，
        // 和书架多选里「上传」按钮置灰是同一个判据。
        if (showContinueDialog) {
            when (continueStage) {
                ContinueStage.PICK_TARGET -> AlertDialog(
                    onDismissRequest = { showContinueDialog = false },
                    title = { Text(text = stringResource(SYMR.strings.download_continue_all)) },
                    text = {
                        Column {
                            ContinueAllActionRow(
                                text = stringResource(SYMR.strings.action_download),
                                onClick = {
                                    showContinueDialog = false
                                    scope.launch {
                                        val queued = screenModel.enqueueAllUnfinishedDownloads()
                                        val message = if (queued > 0) {
                                            context.stringResource(
                                                SYMR.strings.download_continue_all_queued,
                                                queued,
                                            )
                                        } else {
                                            context.stringResource(SYMR.strings.download_continue_all_nothing)
                                        }
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                            )
                            ContinueAllActionRow(
                                text = stringResource(SYMR.strings.action_upload),
                                enabled = state.isUploadAvailable,
                                onClick = { continueStage = ContinueStage.PICK_UPLOAD_SCOPE },
                            )
                            ContinueAllActionRow(
                                text = stringResource(SYMR.strings.action_download_and_upload),
                                enabled = state.isUploadAvailable,
                                onClick = {
                                    showContinueDialog = false
                                    continueStage = ContinueStage.PICK_TARGET
                                    scope.launch {
                                        val queued = screenModel.enqueueAllUnfinishedDownloads()
                                        val message = if (queued > 0) {
                                            context.stringResource(
                                                SYMR.strings.download_continue_all_queued,
                                                queued,
                                            )
                                        } else {
                                            context.stringResource(SYMR.strings.download_continue_all_nothing)
                                        }
                                        snackbarHostState.showSnackbar(message)
                                    }
                                    enqueueAllForUpload()
                                },
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showContinueDialog = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )

                ContinueStage.PICK_UPLOAD_SCOPE -> AlertDialog(
                    onDismissRequest = {
                        showContinueDialog = false
                        continueStage = ContinueStage.PICK_TARGET
                    },
                    title = { Text(text = stringResource(SYMR.strings.upload_scope_dialog_title)) },
                    text = {
                        Column {
                            ContinueAllActionRow(
                                text = stringResource(SYMR.strings.upload_scope_previous),
                                onClick = {
                                    showContinueDialog = false
                                    continueStage = ContinueStage.PICK_TARGET
                                    // 接着上次退出时留下的队列跑：`startUploads()` 会放开
                                    // 「恢复队列等用户点继续」那个闸门（见 UploadManager）。
                                    screenModel.resumeUploads()
                                },
                            )
                            ContinueAllActionRow(
                                text = stringResource(SYMR.strings.upload_scope_all),
                                onClick = {
                                    showContinueDialog = false
                                    continueStage = ContinueStage.PICK_TARGET
                                    enqueueAllForUpload()
                                },
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showContinueDialog = false
                                continueStage = ContinueStage.PICK_TARGET
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
        }

        // 「暂停」弹窗：与「继续」对称，同样三选（下载 / 上传 / 下载和上传）。
        // 上传和下载是互不影响的独立开关，所以「暂停上传」不会停掉正在跑的下载。
        if (showPauseDialog) {
            AlertDialog(
                onDismissRequest = { showPauseDialog = false },
                title = { Text(text = stringResource(SYMR.strings.download_pause_dialog_title)) },
                text = {
                    Column {
                        ContinueAllActionRow(
                            text = stringResource(SYMR.strings.action_download),
                            onClick = {
                                showPauseDialog = false
                                screenModel.pauseDownloads()
                            },
                        )
                        ContinueAllActionRow(
                            text = stringResource(SYMR.strings.action_upload),
                            onClick = {
                                showPauseDialog = false
                                screenModel.pauseUploads()
                            },
                        )
                        ContinueAllActionRow(
                            text = stringResource(SYMR.strings.action_download_and_upload),
                            onClick = {
                                showPauseDialog = false
                                screenModel.pauseDownloadsAndUploads()
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPauseDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        // SY <--

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        // SY -->
        val recSearchState by screenModel.recommendationSearch.status.collectAsState()
        LaunchedEffect(recSearchState) {
            when (val current = recSearchState) {
                is SearchStatus.Finished.WithResults -> {
                    RecommendsScreen.Args.MergedSourceMangas(current.results)
                        .let(::RecommendsScreen)
                        .let(navigator::push)

                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }

                is SearchStatus.Finished.WithoutResults -> {
                    context.toast(SYMR.strings.rec_no_results)
                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }

                is SearchStatus.Cancelling -> {
                    screenModel.cancelRecommendationSearch()
                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }

                else -> {}
            }
        }
        // SY <--

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
