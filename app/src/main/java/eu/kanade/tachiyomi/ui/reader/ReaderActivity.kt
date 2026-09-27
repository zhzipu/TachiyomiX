package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.reader.ChapterListDialog
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReaderProcessingStatusIndicator
import eu.kanade.presentation.reader.ReaderSystemTimeIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.EnhancementSettingsDialog
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.spatial.DepthSpatialModel
import eu.kanade.tachiyomi.ui.reader.spatial.DepthSpatialPipeline
import eu.kanade.tachiyomi.ui.reader.spatial.SpatialDepthSceneIO
import eu.kanade.tachiyomi.ui.reader.spatial.SpatialSceneControlsView
import eu.kanade.tachiyomi.ui.reader.spatial.SpatialSceneView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import exh.source.isEhBasedSource
import exh.ui.ifSourcesLoaded
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class ReaderActivity : BaseActivity() {

    companion object {

        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            /* SY --> */
            page: Int? = null, /* SY <-- */
        ): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                // SY -->
                putExtra("page", page)
                // SY <--
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        const val SHIFT_DOUBLE_PAGES = "shiftingDoublePages"
        const val SHIFTED_PAGE_INDEX = "shiftedPageIndex"
        const val SHIFTED_CHAP_INDEX = "shiftedChapterIndex"
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    // SY -->
    private val sourceManager = Injekt.get<SourceManager>()
    // SY <--

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    var isScrollingThroughPages = false
        private set

    // SY -->
    // 空间深度（立体视差）阅读状态：与参考实现一致，全部保存在 Activity 内
    private val spatialPipeline by lazy { DepthSpatialPipeline(applicationContext) }
    private val spatialModel by lazy { DepthSpatialModel(applicationContext) }
    private var spatialSceneView: SpatialSceneView? = null
    private var spatialSceneContainer: FrameLayout? = null
    private var spatialSceneControls: SpatialSceneControlsView? = null
    private var spatialSceneControlsContainer: View? = null
    private var spatialEdgeExpandButton: View? = null
    private var spatialSceneJob: Job? = null
    private var spatialScenePageKey: Pair<Long?, Int>? = null
    private var spatialMotionSensitivity = 1f
    private var spatialDepthStrength = 1f
    private var spatialRotationAngleX = 7.9f
    private var spatialRotationAngleY = 6.2f
    private var spatialRotationAngleZ = 4.5f

    // 当前页是否为「双页/跨页」显示；空间深度仅支持单页，用于给出明确提示
    private var currentPageHasExtraPage = false
    private var spatialSceneActive by mutableStateOf(false)
    private var spatialSceneBusy by mutableStateOf(false)

    /** 生成期间的进度快照，仅在 [spatialSceneBusy] 为 true 时有意义。 */
    private var spatialProgress by mutableStateOf<DepthSpatialPipeline.Progress?>(null)
    private var showSpatialModelDownloadDialog by mutableStateOf(false)
    private var spatialModelDownloadProgress by mutableStateOf<Int?>(null)
    private var spatialModelCompileHtpVersion by mutableStateOf<Int?>(null)
    // SY <--

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        // 空间深度开启时，返回键先关闭立体视差覆盖层，而不是直接退出阅读器
        onBackPressedDispatcher.addCallback(this) {
            if (spatialSceneActive || spatialSceneBusy) {
                hideSpatialScene()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        if (viewModel.needsInit()) {
            val manga = intent.extras?.getLong("manga", -1) ?: -1L
            val chapter = intent.extras?.getLong("chapter", -1) ?: -1L
            // SY -->
            val page = intent.extras?.getInt("page", -1).takeUnless { it == -1 }
            // SY <--
            if (manga == -1L || chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)

            lifecycleScope.launchNonCancellable {
                val initResult = viewModel.init(manga, chapter/* SY --> */, page/* SY <-- */)
                if (!initResult.getOrDefault(false)) {
                    val exception = initResult.exceptionOrNull() ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(exception)
                    }
                }
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)
        enableExhAutoScroll()

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }

                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }

                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }

                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }

                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page /* SY --> */, event.secondPage /* SY <-- */)
                    }

                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }

                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)

        // 图像增强：开启后预先加载原生库/模型，避免第一次翻页时才初始化
        if (readerPreferences.waifu2xEnabled().get()) {
            Waifu2x.init(this, readerPreferences.waifu2xNoiseLevel().get())
        }
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val showSystemTime by readerPreferences.showSystemTime.collectAsState()
        val showProcessingStatus by readerPreferences.realCuganShowStatus().collectAsState()
        val imageEnhancementEnabled by readerPreferences.realCuganEnabled().collectAsState()
        val enhancementStatus by ImageEnhancer.status.collectAsState()
        // 有无可用 AI 模型包：卸载所有模型包后图像增强不可用，左下角也不应再显示处理状态
        val enhancementAvailable by remember { ModelPackManager.models }.collectAsState()
        val settingsScreenModel = remember {
            ReaderSettingsScreenModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 底部区域（章节导航 + 底栏）的实际高度，底栏升起时用它把左下角状态抬到上方
            var bottomBarsHeight by remember { mutableStateOf(0.dp) }

            // 进入阅读器即同步一次已安装模型包列表，确保 enhancementAvailable 正确反映有无插件
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    ModelPackManager.installedModels(applicationContext)
                }
            }

            if (!state.menuVisible && showPageNumber && !state.pageIndicatorHidden) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    indicatorText = state.pageIndicatorText,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            if (!state.menuVisible && showSystemTime) {
                ReaderSystemTimeIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding(),
                )
            }

            if (showProcessingStatus && imageEnhancementEnabled && enhancementAvailable.isNotEmpty()) {
                ReaderProcessingStatusIndicator(
                    status = enhancementStatus,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .then(
                            if (state.menuVisible && bottomBarsHeight > 0.dp) {
                                // 底栏升起：整体上移到底栏之上，避免被底栏与其中的进度条遮挡
                                Modifier.padding(bottom = bottomBarsHeight)
                            } else {
                                Modifier.navigationBarsPadding()
                            },
                        ),
                )
            }

            ContentOverlay(state = state)

            AppBars(state = state, onBottomSectionHeightChanged = { bottomBarsHeight = it })
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }

            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    screenModel = settingsScreenModel,
                )
            }

            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode.get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }

            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }

            is ReaderViewModel.Dialog.EnhancementSettings -> {
                EnhancementSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                )
            }

            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                    onShareCombined = viewModel::shareImages,
                    onSaveCombined = viewModel::saveImages,
                    hasExtraPage = (state.dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage != null,
                )
            }

            is ReaderViewModel.Dialog.ChapterList -> {
                var chapters by remember {
                    mutableStateOf(viewModel.getChapters())
                }
                ChapterListDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    chapters = chapters,
                    onClickChapter = {
                        viewModel.loadNewChapterFromDialog(it)
                        onDismissRequest()
                    },
                    onBookmark = { chapter ->
                        viewModel.toggleBookmark(chapter.id, !chapter.bookmark)
                        chapters = chapters.map {
                            if (it.chapter.id == chapter.id) {
                                it.copy(chapter = chapter.copy(bookmark = !chapter.bookmark))
                            } else {
                                it
                            }
                        }
                    },
                    state.dateRelativeTime,
                )
            }
            // SY -->
            ReaderViewModel.Dialog.AutoScrollHelp -> AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                title = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help)) },
                text = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help_message)) },
            )

            ReaderViewModel.Dialog.BoostPageHelp -> AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                title = { Text(text = stringResource(SYMR.strings.eh_boost_page_help)) },
                text = { Text(text = stringResource(SYMR.strings.eh_boost_page_help_message)) },
            )

            ReaderViewModel.Dialog.RetryAllHelp -> AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                title = { Text(text = stringResource(SYMR.strings.eh_retry_all_help)) },
                text = { Text(text = stringResource(SYMR.strings.eh_retry_all_help_message)) },
            )
            // SY <--
            null -> {}
        }

        // 空间深度：深度模型体积较大，需用户确认后再下载
        if (showSpatialModelDownloadDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (spatialModelDownloadProgress == null) showSpatialModelDownloadDialog = false
                },
                title = { Text(stringResource(MR.strings.reader_spatial_scene_download_title)) },
                text = {
                    val progress = spatialModelDownloadProgress
                    if (progress == null) {
                        Text(stringResource(MR.strings.reader_spatial_scene_download_message))
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.reader_spatial_scene_downloading, progress))
                        }
                    }
                },
                confirmButton = {
                    if (spatialModelDownloadProgress == null) {
                        TextButton(onClick = ::downloadSpatialModel) {
                            Text(stringResource(MR.strings.reader_spatial_scene_download))
                        }
                    }
                },
                dismissButton = {
                    if (spatialModelDownloadProgress == null) {
                        TextButton(onClick = { showSpatialModelDownloadDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                },
            )
        }

        // 空间深度：生成期间显示阻塞式进度弹窗。弹窗存在时无法操作其他区域，
        // 只能点「取消」中断，或等生成完成后自动关闭。
        if (spatialSceneBusy) {
            val progress = spatialProgress
            val percent = progress?.percent
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text(stringResource(MR.strings.reader_spatial_scene_progress_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (percent != null) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(spatialProgressStageText(progress?.stage))
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = ::hideSpatialScene) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    /** 生成阶段对应的说明文字。 */
    @Composable
    private fun spatialProgressStageText(stage: DepthSpatialPipeline.Stage?): String = when (stage) {
        DepthSpatialPipeline.Stage.WAITING_ENHANCEMENT -> {
            stringResource(MR.strings.reader_spatial_scene_stage_waiting_enhancement)
        }
        DepthSpatialPipeline.Stage.COMPILING -> {
            stringResource(
                MR.strings.reader_spatial_scene_compiling,
                spatialModelCompileHtpVersion?.let { "v$it" } ?: "HTP",
            )
        }
        DepthSpatialPipeline.Stage.INFERRING -> stringResource(MR.strings.reader_spatial_scene_stage_inferring)
        else -> stringResource(MR.strings.reader_spatial_scene_stage_preparing)
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        hideSpatialScene()
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        spatialSceneView?.stopMotion()
        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }
        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        spatialSceneView?.startMotion()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(
        state: ReaderViewModel.State,
        onBottomSectionHeightChanged: (Dp) -> Unit = {},
    ) {
        if (!ifSourcesLoaded()) {
            return
        }

        val isHttpSource = viewModel.getSource() is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())

        // SY -->
        val readingMode = viewModel.getMangaReadingMode()
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        val cropBorderContinuousVertical by readerPreferences.cropBordersContinuousVertical.collectAsState()
        val cropEnabled = if (isPagerType) {
            cropBorderPaged
        } else if (isWebtoon) {
            cropBorderWebtoon
        } else {
            cropBorderContinuousVertical
        }
        val readerBottomButtons by remember {
            readerPreferences.readerBottomButtons.changes()
        }.collectAsState(emptySet())
        val dualPageSplitPaged by readerPreferences.dualPageSplitPaged.collectAsState()
        val imageEnhancementEnabled by readerPreferences.realCuganEnabled().collectAsState()
        // 有无可用 AI 模型包：无任何模型包时图像增强不可用（底栏图标置灰、开关被禁用）
        val enhancementAvailable by remember { ModelPackManager.models }.collectAsState()
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                ModelPackManager.installedModels(applicationContext)
            }
        }
        // SY <--

        val verticalNavigatorModes by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigator = verticalNavigatorModes.contains(
            ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
        )
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()
        val verticalNavigatorHeight by readerPreferences.verticalNavigatorHeight.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            // bookmarked = state.bookmarked,
            // onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            chapterNavigatorType = if (!verticalNavigator) {
                if (state.viewer is R2LPagerViewer) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = verticalNavigatorHeight / 100f,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },
            onPageIndexChangeFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
            // SY -->
            isExhToolsVisible = state.ehUtilsVisible,
            onSetExhUtilsVisibility = viewModel::showEhUtils,
            isAutoScroll = state.autoScroll,
            isAutoScrollEnabled = state.isAutoScrollEnabled,
            onToggleAutoscroll = viewModel::toggleAutoScroll,
            autoScrollFrequency = state.ehAutoscrollFreq,
            onSetAutoScrollFrequency = viewModel::setAutoScrollFrequency,
            onClickAutoScrollHelp = viewModel::openAutoScrollHelpDialog,
            onClickRetryAll = ::exhRetryAll,
            onClickRetryAllHelp = viewModel::openRetryAllHelp,
            onClickBoostPage = ::exhBoostPage,
            onClickBoostPageHelp = viewModel::openBoostPageHelp,
            currentPageText = state.currentPageText,
            enabledButtons = readerBottomButtons,
            currentReadingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = true),
            ),
            dualPageSplitEnabled = dualPageSplitPaged,
            doublePages = state.doublePages,
            onClickChapterList = viewModel::openChapterListDialog,
            onClickPageLayout = {
                if (readerPreferences.pageLayout.get() == PagerConfig.PageLayout.AUTOMATIC) {
                    (viewModel.state.value.viewer as? PagerViewer)?.config?.let { config ->
                        config.doublePages = !config.doublePages
                        reloadChapters(config.doublePages, true)
                    }
                } else {
                    readerPreferences.pageLayout.set(1 - readerPreferences.pageLayout.get())
                }
            },
            onClickShiftPage = ::shiftDoublePages,
            onLongClickShiftPage = ::invertDoublePages,
            onClickImageEnhancement = {
                // 无可用模型包时不允许切换（按钮已置灰）
                if (enhancementAvailable.isNotEmpty()) {
                    val enabled = !readerPreferences.realCuganEnabled().get()
                    readerPreferences.realCuganEnabled().set(enabled)
                    menuToggleToast?.cancel()
                    menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
                }
            },
            imageEnhancementEnabled = imageEnhancementEnabled,
            enhancementAvailable = enhancementAvailable.isNotEmpty(),
            onClickEnhancementSettings = viewModel::openEnhancementSettingsDialog,
            spatialSceneActive = spatialSceneActive,
            spatialSceneBusy = spatialSceneBusy,
            onClickSpatialScene = ::toggleSpatialScene,
            // SY <--
            onBottomSectionHeightChanged = onBottomSectionHeightChanged,
        )
    }

    private fun enableExhAutoScroll() {
        readerPreferences.autoscrollInterval.changes()
            .combine(viewModel.state.map { it.autoScroll }.distinctUntilChanged()) { interval, enabled ->
                interval.toDouble() to enabled
            }.mapLatest { (intervalFloat, enabled) ->
                if (enabled) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val interval = intervalFloat.seconds
                        while (true) {
                            if (!viewModel.state.value.menuVisible) {
                                viewModel.state.value.viewer.let { v ->
                                    when (v) {
                                        is PagerViewer -> v.moveToNext()
                                        is WebtoonViewer -> {
                                            if (readerPreferences.smoothAutoScroll.get()) {
                                                v.linearScroll(interval)
                                            } else {
                                                v.scrollDown()
                                            }
                                        }
                                    }
                                }
                                delay(interval)
                            } else {
                                delay(100)
                            }
                        }
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun exhRetryAll() {
        var retried = 0

        viewModel.state.value.viewerChapters
            ?.currChapter
            ?.pages
            ?.forEachIndexed { _, page ->
                var shouldQueuePage = false
                if (page.status is Page.State.Error) {
                    shouldQueuePage = true
                } /*else if (page.status == Page.LOAD_PAGE ||
                                    page.status == Page.DOWNLOAD_IMAGE) {
                                // Do nothing
                            }*/

                if (shouldQueuePage) {
                    page.status = Page.State.Queue
                } else {
                    return@forEachIndexed
                }

                // If we are using EHentai/ExHentai, get a new image URL
                viewModel.manga?.let { m ->
                    val src = sourceManager.get(m.source)
                    if (src?.isEhBasedSource() == true) {
                        page.imageUrl = null
                    }
                }

                val loader = page.chapter.pageLoader
                if (page.index == exhCurrentpage()?.index && loader is HttpPageLoader) {
                    loader.boostPage(page)
                } else {
                    loader?.retryPage(page)
                }

                retried++
            }

        toast(pluralStringResource(SYMR.plurals.eh_retry_toast, retried, retried))
    }

    private fun exhBoostPage() {
        viewModel.state.value.viewer ?: return
        val curPage = exhCurrentpage() ?: run {
            toast(SYMR.strings.eh_boost_page_invalid)
            return
        }

        if (curPage.status is Page.State.Error) {
            toast(SYMR.strings.eh_boost_page_errored)
        } else if (curPage.status == Page.State.LoadPage || curPage.status == Page.State.DownloadImage) {
            toast(SYMR.strings.eh_boost_page_downloading)
        } else if (curPage.status == Page.State.Ready) {
            toast(SYMR.strings.eh_boost_page_downloaded)
        } else {
            val loader = (viewModel.state.value.viewerChapters?.currChapter?.pageLoader as? HttpPageLoader)
            if (loader != null) {
                loader.boostPage(curPage)
                toast(SYMR.strings.eh_boost_boosted)
            } else {
                toast(SYMR.strings.eh_boost_invalid_loader)
            }
        }
    }

    private fun exhCurrentpage(): ReaderPage? {
        val viewer = viewModel.state.value.viewer
        val currentPage =
            (((viewer as? PagerViewer)?.currentPage ?: (viewer as? WebtoonViewer)?.currentPage) as? ReaderPage)?.index
        return currentPage?.let { viewModel.state.value.viewerChapters?.currChapter?.pages?.getOrNull(it) }
    }

    fun reloadChapters(doublePages: Boolean, force: Boolean = false) {
        val viewer = viewModel.state.value.viewer as? PagerViewer ?: return
        viewer.updateShifting()
        if (!force && viewer.config.autoDoublePages) {
            setDoublePageMode(viewer)
        } else {
            viewer.config.doublePages = doublePages
            viewModel.setDoublePages(viewer.config.doublePages)
        }
        val currentChapter = viewModel.state.value.currentChapter
        if (doublePages) {
            // If we're moving from singe to double, we want the current page to be the first page
            val currentPage = viewModel.state.value.currentPage
            viewer.config.shiftDoublePage = (
                currentPage + (currentChapter?.pages?.take(currentPage)?.count { it.fullPage || it.isolatedPage } ?: 0)
                ) % 2 != 0
        }
        viewModel.state.value.viewerChapters?.let {
            viewer.setChaptersDoubleShift(it)
        }
    }

    private fun setDoublePageMode(viewer: PagerViewer) {
        val currentOrientation = resources.configuration.orientation
        viewer.config.doublePages = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        viewModel.setDoublePages(viewer.config.doublePages)
    }

    private fun shiftDoublePages() {
        val viewer = viewModel.state.value.viewer as? PagerViewer ?: return
        viewer.config.let { config ->
            config.shiftDoublePage = !config.shiftDoublePage
            viewModel.state.value.viewerChapters?.let {
                viewer.updateShifting()
                viewer.setChaptersDoubleShift(it)
                invalidateOptionsMenu()
            }
        }
    }

    // 长按双页切换按钮 = 反转双页左右顺序（1|2 <-> 2|1）
    private fun invertDoublePages() {
        val viewer = viewModel.state.value.viewer as? PagerViewer ?: return
        if (viewer.currentSpread()?.second == null) return // 非双页显示不做处理
        val oldInvert = viewer.config.invertDoublePages // 换位前状态：决定动画里哪页在右
        // 同步切换运行时配置（读双页方向/编号都以它为准）
        viewer.config.invertDoublePages = !oldInvert
        // 原位重合并 + 刷新页码（插底层，不清空、不重建，避免黑屏），并播放换位对滑动画
        viewer.invertCurrentSpread()
        viewer.animateDoublePageSwap(oldInvert)
        invalidateOptionsMenu()
    }
// EXH <--

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        // SY -->
        if (newViewer is PagerViewer) {
            if (readerPreferences.pageLayout.get() == PagerConfig.PageLayout.AUTOMATIC) {
                setDoublePageMode(newViewer)
            }
            viewModel.state.value.lastShiftDoubleState?.let { newViewer.config.shiftDoublePage = it }
        }

        val manga = viewModel.state.value.manga
        val defaultReaderType = manga?.defaultReaderType(
            manga.mangaType(sourceName = sourceManager.get(manga.source)?.name),
        )
        if (
            readerPreferences.useAutoWebtoon.get() &&
            (manga?.readingMode?.toInt() ?: ReadingMode.DEFAULT.flagValue) == ReadingMode.DEFAULT.flagValue &&
            defaultReaderType != null &&
            defaultReaderType == ReadingMode.WEBTOON.flagValue
        ) {
            readingModeToast?.cancel()
            readingModeToast = toast(SYMR.strings.eh_auto_webtoon_snack)
        } else if (readerPreferences.showReadingMode.get()) {
            // SY <--
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        // SY -->
        val state = viewModel.state.value
        if (state.indexChapterToShift != null && state.indexPageToShift != null) {
            viewerChapters.currChapter.pages?.find {
                it.index == state.indexPageToShift && it.chapter.chapter.id == state.indexChapterToShift
            }?.let {
                (viewModel.state.value.viewer as? PagerViewer)?.updateShifting(it)
            }
            viewModel.setIndexChapterToShift(null)
            viewModel.setIndexPageToShift(null)
        } else if (state.lastShiftDoubleState != null) {
            val currentChapter = viewerChapters.currChapter
            (viewModel.state.value.viewer as? PagerViewer)?.config?.shiftDoublePage = (
                currentChapter.requestedPage +
                    (
                        currentChapter.pages?.take(currentChapter.requestedPage)
                            ?.count { it.fullPage || it.isolatedPage } ?: 0
                        )
                ) % 2 != 0
        }
        // SY <--

        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    @SuppressLint("SetTextI18n")
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean = false) {
        // SY -->
        // 翻页后空间深度覆盖层对应的是旧页面，需要先关闭
        currentPageHasExtraPage = hasExtraPage
        val selectedKey = page.chapter.chapter.id to page.index
        if (spatialScenePageKey != null && spatialScenePageKey != selectedKey) {
            hideSpatialScene()
        }
        // SY <--
        val currentPageText = if (hasExtraPage) {
            val invertDoublePage = (viewModel.state.value.viewer as? PagerViewer)?.config?.invertDoublePages ?: false
            if ((resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) xor
                invertDoublePage
            ) {
                "${page.number}-${page.number + 1}"
            } else {
                "${page.number + 1}-${page.number}"
            }
        } else {
            "${page.number}"
        }
        // 阅读器内的页数指示器文本：双页跨页时「左页-总页数-右页」（如 1-18-2），
        // 单页显示（含奇数页单独显示、跨页拆分）留空，走默认的「当前页 / 总页数」
        val rightPage = (viewModel.state.value.viewer as? PagerViewer)?.currentSpread()?.second
        val pageIndicatorText = if (hasExtraPage && rightPage != null && rightPage.number != page.number) {
            val totalPages = page.chapter.pages?.count() ?: 0
            val invertDoublePage = (viewModel.state.value.viewer as? PagerViewer)?.config?.invertDoublePages ?: false
            // 切换显示顺序（invertDoublePages 与 RTL 异或）时自动交换左右页数
            val isLtr = (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) xor
                invertDoublePage
            val left = if (isLtr) page.number else rightPage.number
            val right = if (isLtr) rightPage.number else page.number
            "$left-$totalPages-$right"
        } else {
            ""
        }
        viewModel.onPageSelected(page, currentPageText, hasExtraPage, pageIndicatorText)
        // SY <--
    }

    /** 过渡页（章节切换页）成为当前页时调用：阅读器中不显示页码。 */
    fun onPageSelected(transition: ChapterTransition) {
        viewModel.onTransitionPageSelected()
    }

    /**
     * 「空间深度模型」按钮的切换入口：不可用时给出明确原因，可用时生成并叠加立体视差视图。
     */
    private fun toggleSpatialScene() {
        if (spatialSceneActive || spatialSceneBusy) {
            hideSpatialScene()
            return
        }
        val page = (viewModel.state.value.viewer as? PagerViewer)?.currentPage as? ReaderPage
        // 空间深度只支持分页阅读的单页显示，双页/跨页或非分页阅读模式直接提示
        if (page == null || currentPageHasExtraPage) {
            menuToggleToast?.cancel()
            menuToggleToast = toast(MR.strings.reader_spatial_scene_single_page_only)
            return
        }

        spatialSceneBusy = true
        spatialProgress = null
        spatialScenePageKey = page.chapter.chapter.id to page.index
        spatialSceneJob = lifecycleScope.launch {
            try {
                when (
                    val result = spatialPipeline.create(
                        page = page,
                        onCompilationStarted = { htpArchitecture ->
                            spatialModelCompileHtpVersion = htpArchitecture
                        },
                        onProgress = { progress ->
                            spatialProgress = progress
                        },
                    )
                ) {
                    is DepthSpatialPipeline.Result.Ready -> {
                        val scene = runCatching {
                            withContext(Dispatchers.IO) { SpatialDepthSceneIO.read(result.file) }
                        }.getOrElse { error ->
                            spatialSceneBusy = false
                            spatialScenePageKey = null
                            menuToggleToast = toast(
                                stringResource(MR.strings.reader_spatial_scene_failed, error.message.orEmpty()),
                            )
                            return@launch
                        }
                        // 生成期间可能已经翻页或关闭，避免给过期页面挂上新视图
                        if (spatialScenePageKey != (page.chapter.chapter.id to page.index)) return@launch
                        val sceneView = SpatialSceneView(this@ReaderActivity).apply {
                            showScene(scene)
                            setMotionSensitivity(spatialMotionSensitivity)
                            setDepthStrength(spatialDepthStrength)
                            setRotationAngles(
                                spatialRotationAngleX,
                                spatialRotationAngleY,
                                spatialRotationAngleZ,
                            )
                            setOnClickListener { toggleMenu() }
                        }
                        val sceneContainer = FrameLayout(this@ReaderActivity).apply {
                            addView(sceneView, MATCH_PARENT, MATCH_PARENT)
                        }
                        // 控件区域边距与宽度，以及右侧边缘的“<<”展开按钮
                        val controlMargin = (16 * resources.displayMetrics.density + 0.5f).toInt()
                        val controlWidth = (110 * resources.displayMetrics.density + 0.5f).toInt()
                        // 退出按钮：位于右侧菜单下方，独立于菜单面板之外
                        val exitButton = TextView(this@ReaderActivity).apply {
                            text = stringResource(MR.strings.reader_spatial_scene_exit)
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            gravity = Gravity.CENTER
                            minHeight = (40 * resources.displayMetrics.density + 0.5f).toInt()
                            background = GradientDrawable().apply {
                                cornerRadius = 16 * resources.displayMetrics.density
                                setColor(Color.argb(230, 28, 27, 31))
                                setStroke(
                                    (1 * resources.displayMetrics.density).toInt(),
                                    Color.argb(120, 255, 255, 255),
                                )
                            }
                        }
                        exitButton.setOnClickListener { hideSpatialScene() }
                        val edgeExpandButton = TextView(this@ReaderActivity).apply {
                            text = "<<"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            gravity = Gravity.CENTER
                            minWidth = (36 * resources.displayMetrics.density).toInt()
                            minHeight = (44 * resources.displayMetrics.density).toInt()
                            setBackgroundColor(Color.argb(230, 28, 27, 31))
                            visibility = View.GONE
                            alpha = 0f
                        }
                        edgeExpandButton.setOnClickListener {
                            spatialSceneControls?.animate()?.translationX(0f)
                                ?.setDuration(260)
                                ?.setInterpolator(DecelerateInterpolator())
                                ?.start()
                            // 菜单回到屏幕内，退出按钮一并恢复
                            exitButton.visibility = View.VISIBLE
                            edgeExpandButton.animate().alpha(0f).setDuration(180).withEndAction {
                                edgeExpandButton.visibility = View.GONE
                            }
                        }
                        binding.root.addView(
                            edgeExpandButton,
                            FrameLayout.LayoutParams(
                                WRAP_CONTENT,
                                WRAP_CONTENT,
                                Gravity.END or Gravity.CENTER_VERTICAL,
                            ),
                        )
                        val spatialEdgeExpand = edgeExpandButton
                        spatialEdgeExpandButton = edgeExpandButton
                        val controls = SpatialSceneControlsView(this@ReaderActivity).apply {
                            configure(
                                sensitivity = spatialMotionSensitivity,
                                depthStrength = spatialDepthStrength,
                                rotationAngleX = spatialRotationAngleX,
                                rotationAngleY = spatialRotationAngleY,
                                rotationAngleZ = spatialRotationAngleZ,
                                anchorText = stringResource(MR.strings.reader_spatial_rotation_anchor),
                                anchorPickText = stringResource(MR.strings.reader_spatial_rotation_anchor_pick),
                                sensitivityText = { value ->
                                    stringResource(
                                        MR.strings.reader_spatial_gyro_sensitivity,
                                        String.format(Locale.getDefault(), "%.1f", value),
                                    )
                                },
                                depthText = { value ->
                                    stringResource(
                                        MR.strings.reader_spatial_depth_strength,
                                        String.format(Locale.getDefault(), "%.1f", value),
                                    )
                                },
                                rotationText = { axis, value ->
                                    stringResource(
                                        MR.strings.reader_spatial_rotation_angle,
                                        axis,
                                        String.format(Locale.getDefault(), "%.1f", value),
                                    )
                                },
                                rotationButtonText = stringResource(MR.strings.reader_spatial_rotation_angles),
                                rotationResetText = stringResource(MR.strings.reader_spatial_rotation_reset),
                                gyroResetText = stringResource(MR.strings.reader_spatial_gyro_reset),
                                collapseText = ">>",
                                onDismissRequested = {
                                    // 菜单收起动画由控件自身处理，这里仅显示右侧“<<”展开按钮
                                    spatialEdgeExpand.visibility = View.VISIBLE
                                    spatialEdgeExpand.animate().alpha(1f).setDuration(180).start()
                                    // 退出按钮跟随菜单一起收起
                                    exitButton.visibility = View.GONE
                                },
                                onAnchorRequested = {
                                    sceneView.beginRotationAnchorSelection {
                                        completeAnchorSelection()
                                        menuToggleToast?.cancel()
                                        menuToggleToast = toast(MR.strings.reader_spatial_rotation_anchor_set)
                                    }
                                },
                                onGyroscopeReset = {
                                    sceneView.resetGyroscope()
                                    menuToggleToast?.cancel()
                                    menuToggleToast = toast(MR.strings.reader_spatial_gyro_reset_done)
                                },
                                onSensitivityChanged = { sensitivity ->
                                    spatialMotionSensitivity = sensitivity
                                    sceneView.setMotionSensitivity(sensitivity)
                                },
                                onDepthStrengthChanged = { strength ->
                                    spatialDepthStrength = strength
                                    sceneView.setDepthStrength(strength)
                                },
                                onRotationAnglesChanged = { xDegrees, yDegrees, zDegrees ->
                                    spatialRotationAngleX = xDegrees
                                    spatialRotationAngleY = yDegrees
                                    spatialRotationAngleZ = zDegrees
                                    sceneView.setRotationAngles(xDegrees, yDegrees, zDegrees)
                                },
                            )
                        }
                        // 菜单与退出按钮纵向排列：退出按钮在菜单面板之外的下方
                        val controlsContainer = LinearLayout(this@ReaderActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                            addView(
                                exitButton,
                                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                                    topMargin = (8 * resources.displayMetrics.density + 0.5f).toInt()
                                },
                            )
                        }
                        binding.root.addView(
                            controlsContainer,
                            FrameLayout.LayoutParams(
                                controlWidth,
                                WRAP_CONTENT,
                                Gravity.END or Gravity.CENTER_VERTICAL,
                            ).apply {
                                marginEnd = controlMargin
                            },
                        )
                        // 悬浮控件避开系统栏
                        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { view, insets ->
                            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                                params.marginEnd = controlMargin + systemBars.right
                                view.layoutParams = params
                            }
                            insets
                        }
                        binding.viewerContainer.addView(sceneContainer, MATCH_PARENT, MATCH_PARENT)
                        sceneView.startMotion()
                        spatialSceneView = sceneView
                        spatialSceneContainer = sceneContainer
                        spatialSceneControls = controls
                        spatialSceneControlsContainer = controlsContainer
                        spatialSceneActive = true
                        // 生成完成后直接全屏看漫画
                        hideMenu()
                    }
                    DepthSpatialPipeline.Result.ModelMissing -> {
                        // 缺少 Depth Anything V3 模型，询问是否下载
                        showSpatialModelDownloadDialog = true
                        spatialScenePageKey = null
                    }
                    is DepthSpatialPipeline.Result.RuntimeUnavailable -> {
                        // 非骁龙 HTP / Android 版本过低 / 缺少 QNN 运行库；有原生细节时一并显示便于定位
                        menuToggleToast = toast(
                            if (result.detail.isBlank()) {
                                stringResource(MR.strings.reader_spatial_scene_runtime_unavailable)
                            } else {
                                stringResource(MR.strings.reader_spatial_scene_failed, result.detail)
                            },
                        )
                        spatialScenePageKey = null
                    }
                    is DepthSpatialPipeline.Result.Failed -> {
                        menuToggleToast = toast(
                            stringResource(
                                MR.strings.reader_spatial_scene_failed,
                                result.cause.message.orEmpty(),
                            ),
                        )
                        spatialScenePageKey = null
                    }
                }
            } finally {
                spatialModelCompileHtpVersion = null
                spatialSceneBusy = false
                spatialProgress = null
                spatialSceneJob = null
            }
        }
    }

    /**
     * 关闭并释放空间深度覆盖层（同时取消正在进行的生成任务）。
     */
    private fun hideSpatialScene() {
        spatialSceneJob?.cancel()
        spatialSceneJob = null
        spatialModelCompileHtpVersion = null
        spatialSceneBusy = false
        spatialProgress = null
        spatialScenePageKey = null
        spatialSceneView?.let { view ->
            view.release()
        }
        if (::binding.isInitialized) {
            spatialSceneContainer?.let(binding.viewerContainer::removeView)
            // 菜单与退出按钮都在这个容器里，移除容器即可
            spatialSceneControlsContainer?.let(binding.root::removeView)
            spatialEdgeExpandButton?.let {
                it.animate().cancel()
                it.alpha = 0f
                it.translationX = 0f
                it.visibility = View.GONE
            }
        }
        spatialSceneView = null
        spatialSceneContainer = null
        spatialSceneControls = null
        spatialSceneControlsContainer = null
        spatialEdgeExpandButton = null
        spatialSceneActive = false
    }

    /**
     * 下载 Depth Anything V3 深度模型（约 101MB），完成后自动重试打开空间深度。
     */
    private fun downloadSpatialModel() {
        if (spatialModelDownloadProgress != null) return
        spatialModelDownloadProgress = 0
        lifecycleScope.launch {
            runCatching {
                spatialModel.download { downloaded, total ->
                    spatialModelDownloadProgress = if (total > 0L) {
                        (downloaded * 100L / total).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                }
            }.onSuccess {
                spatialModelDownloadProgress = null
                showSpatialModelDownloadDialog = false
                toggleSpatialScene()
            }.onFailure { error ->
                spatialModelDownloadProgress = null
                showSpatialModelDownloadDialog = false
                menuToggleToast = toast(
                    stringResource(
                        MR.strings.reader_spatial_scene_download_failed,
                        error.message.orEmpty(),
                    ),
                )
            }
        }
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage, extraPage: ReaderPage? = null) {
        // SY -->
        viewModel.openPageDialog(page, extraPage)
        // SY <--
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    fun onShareImageResult(uri: Uri, page: ReaderPage /* SY --> */, secondPage: ReaderPage? = null /* SY <-- */) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        // SY -->
        val text = if (secondPage != null) {
            stringResource(
                SYMR.strings.share_pages_info, manga.title, chapter.name,
                if (resources.configuration.layoutDirection ==
                    View.LAYOUT_DIRECTION_LTR
                ) {
                    "${page.number}-${page.number + 1}"
                } else {
                    "${page.number + 1}-${page.number}"
                },
            )
        } else {
            stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number)
        }
        // SY <--

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = /* SY --> */ text, // SY <--
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }

            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile.changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)

            // SY -->
            readerPreferences.pageLayout.changes()
                .drop(1)
                .onEach {
                    viewModel.setDoublePages(
                        (viewModel.state.value.viewer as? PagerViewer)
                            ?.config
                            ?.doublePages
                            ?: false,
                    )
                }
                .launchIn(lifecycleScope)

            readerPreferences.dualPageSplitPaged.changes()
                .drop(1)
                .onEach {
                    if (viewModel.state.value.viewer !is PagerViewer) return@onEach
                    reloadChapters(
                        !it &&
                            when (readerPreferences.pageLayout.get()) {
                                PagerConfig.PageLayout.DOUBLE_PAGES -> true
                                PagerConfig.PageLayout.AUTOMATIC ->
                                    resources.configuration.orientation ==
                                        Configuration.ORIENTATION_LANDSCAPE

                                else -> false
                            },
                        true,
                    )
                }
                .launchIn(lifecycleScope)
            // SY <--
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }

                value < 0 -> {
                    0.01f
                }

                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }

        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
