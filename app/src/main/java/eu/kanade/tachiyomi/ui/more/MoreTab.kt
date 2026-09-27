package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.upload.UploadManager
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import eu.kanade.tachiyomi.ui.upload.UploadQueueScreen
import exh.ui.batchadd.BatchAddScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object MoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MoreScreenModel() }
        val downloadQueueState by screenModel.downloadQueueState.collectAsState()
        // SY -->
        val uploadQueueState by screenModel.uploadQueueState.collectAsState()
        // SY <--
        MoreScreen(
            downloadQueueStateProvider = { downloadQueueState },
            // SY -->
            uploadQueueStateProvider = { uploadQueueState },
            // SY <--
            downloadedOnly = screenModel.downloadedOnly,
            onDownloadedOnlyChange = { screenModel.downloadedOnly = it },
            incognitoMode = screenModel.incognitoMode,
            onIncognitoModeChange = { screenModel.incognitoMode = it },
            // SY -->
            showNavUpdates = screenModel.showNavUpdates,
            showNavHistory = screenModel.showNavHistory,
            // SY <--
            onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },
            // SY -->
            onClickUploadQueue = { navigator.push(UploadQueueScreen) },
            // SY <--
            onClickCategories = { navigator.push(CategoryScreen()) },
            onClickStats = { navigator.push(StatsScreen()) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickSettings = { navigator.push(SettingsScreen()) },
            // SY -->
            onClickBatchAdd = { navigator.push(BatchAddScreen()) },
            onClickUpdates = { navigator.push(UpdatesTab) },
            onClickHistory = { navigator.push(HistoryTab) },
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
            // SY <--
        )
    }
}

private class MoreScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    // SY -->
    private val uploadManager: UploadManager = Injekt.get(),
    // SY <--
    preferences: BasePreferences = Injekt.get(),
    // SY -->
    uiPreferences: UiPreferences = Injekt.get(),
    // SY <--
) : ScreenModel {

    var downloadedOnly by preferences.downloadedOnly.asState(screenModelScope)
    var incognitoMode by preferences.incognitoMode.asState(screenModelScope)

    // SY -->
    val showNavUpdates by uiPreferences.showNavUpdates.asState(screenModelScope)
    val showNavHistory by uiPreferences.showNavHistory.asState(screenModelScope)
    // SY <--

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    // SY -->
    private var _uploadQueueState: MutableStateFlow<UploadQueueState> = MutableStateFlow(UploadQueueState.Stopped)
    val uploadQueueState: StateFlow<UploadQueueState> = _uploadQueueState.asStateFlow()
    // SY <--

    init {
        // Handle running/paused status change and queue progress updating
        screenModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }

        // SY -->
        // 上传自成一档：队列里排队的 + 正在传的那一本都算「还有几本要做」，
        // 只有「一本都没有」才是 Stopped。下载侧有「暂停后队列为空」的情形，
        // 上传这边暂停时队列一定非空，所以不用为它单独兜一层。
        screenModelScope.launchIO {
            combine(
                uploadManager.isUploaderRunning,
                uploadManager.queueState,
                uploadManager.currentTask,
            ) { isRunning, queue, current -> Triple(isRunning, queue.size, current != null) }
                .collectLatest { (isRunning, queueSize, hasCurrent) ->
                    val pending = queueSize + if (hasCurrent) 1 else 0
                    _uploadQueueState.value = when {
                        pending == 0 -> UploadQueueState.Stopped
                        !isRunning -> UploadQueueState.Paused(pending)
                        else -> UploadQueueState.Uploading(pending)
                    }
                }
        }
        // SY <--
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}

// SY -->
/**
 * 「更多」页上传队列条目的副标题状态。
 *
 * 与 [DownloadQueueState] 一一对应，单位是「本漫画」（上传的任务粒度就是整本）。
 */
sealed interface UploadQueueState {
    /** 队列空且没有任务在跑。 */
    data object Stopped : UploadQueueState

    /** 已暂停，还有 [pending] 本排在队列里。 */
    data class Paused(val pending: Int) : UploadQueueState

    /** 正在跑，还有 [pending] 本没做完（含正在传的那一本）。 */
    data class Uploading(val pending: Int) : UploadQueueState
}
// SY <--
