package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.CustomNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.CustomTapZones
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Configuration used by pager viewers.
 */
class PagerConfig(
    private val viewer: PagerViewer,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ViewerConfig(readerPreferences, scope) {

    var theme = readerPreferences.readerTheme.get()
        private set

    var automaticBackground = false
        private set

    var dualPageSplitChangedListener: ((Boolean) -> Unit)? = null

    var reloadChapterListener: ((Boolean) -> Unit)? = null

    var imageScaleType = 1
        private set

    var imageZoomType = ReaderPageImageView.ZoomStartPosition.LEFT
        private set

    var imageCropBorders = false
        private set

    var navigateToPan = false
        private set

    /**
     * 是否禁止双击缩放。
     *
     * 该值在双击发生时实时读取（见 PagerPageHolder），所以设置变化后不需要重建页面，
     * 与 [navigateToPan] 一样不触发 [imagePropertyChangedListener]。
     */
    var disableDoubleTapZoom = false
        private set

    var landscapeZoom = false
        private set

    // SY -->
    var usePageTransitions = false

    var shiftDoublePage = false

    var doublePages =
        readerPreferences.pageLayout.get() == PageLayout.DOUBLE_PAGES && !readerPreferences.dualPageSplitPaged.get()
        set(value) {
            field = value
            if (!value) {
                shiftDoublePage = false
            }
        }

    var invertDoublePages = false

    var autoDoublePages = readerPreferences.pageLayout.get() == PageLayout.AUTOMATIC

    @ColorInt
    var pageCanvasColor = Color.WHITE

    var centerMarginType = CenterMarginType.NONE

    // SY <--

    var customNavigation = readerPreferences.customNavigationPager.get()
        private set

    init {
        readerPreferences.readerTheme
            .register(
                {
                    theme = it
                    automaticBackground = it == 3
                },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.imageScaleType
            .register({ imageScaleType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.zoomStart
            .register({ zoomTypeFromPreference(it) }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.cropBorders
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigateToPan
            .register({ navigateToPan = it })

        readerPreferences.disableDoubleTapZoom
            .register({ disableDoubleTapZoom = it })

        readerPreferences.landscapeZoom
            .register({ landscapeZoom = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModePager
            .register({ navigationMode = it }, { updateNavigation(navigationMode) })

        readerPreferences.pagerNavInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.pagerNavInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        // SY -->
        readerPreferences.customNavigationPager.changes()
            .drop(1)
            .onEach { value ->
                customNavigation = value
                if (navigationMode == 6) updateNavigation(navigationMode)
            }
            .launchIn(scope)
        // SY <--

        readerPreferences.dualPageSplitPaged
            .register(
                { dualPageSplit = it },
                {
                    imagePropertyChangedListener?.invoke()
                    dualPageSplitChangedListener?.invoke(it)
                },
            )

        readerPreferences.dualPageInvertPaged
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFit
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.dualPageRotateToFitInvert
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() },
            )

        // SY -->
        readerPreferences.pageTransitionsPager
            .register({ usePageTransitions = it }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.readerTheme
            .register(
                {
                    themeToColor(it)
                },
                {
                    themeToColor(it)
                    reloadChapterListener?.invoke(doublePages)
                },
            )
        readerPreferences.pageLayout
            .register(
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                },
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                    reloadChapterListener?.invoke(doublePages)
                },
            )

        readerPreferences.centerMarginType
            .register({ centerMarginType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.invertDoublePages
            .register({ invertDoublePages = it && dualPageSplit == false }, { imagePropertyChangedListener?.invoke() })
        // SY <--

        // 图像增强：增强相关偏好变化时先取消按旧配置排队/进行中的处理，
        // 再让当前可见页按新配置重新增强（刷新后 page holder 会按新配置哈希重新取缓存、
        // 重新排队），否则换了模型/档位画面还是旧结果。
        // 一次改动可能联动多个偏好（例如换模型会顺带纠正降噪档位），这里统一去抖，保证最多重处理一次。
        var enhancementRefreshJob: Job? = null
        fun cancelEnhancementOnChange(
            changes: Flow<Any?>,
            reason: String,
            refreshImages: Boolean = true,
            refreshOnlyWhenEnabled: Boolean = true,
        ) {
            changes.drop(1)
                .onEach {
                    ImageEnhancer.cancelAll(reason)
                    if (!refreshImages) return@onEach
                    // 增强未开启时不做重新处理
                    if (refreshOnlyWhenEnabled && !ReaderEnhancement.isEnabled(readerPreferences)) return@onEach
                    enhancementRefreshJob?.cancel()
                    enhancementRefreshJob = scope.launch {
                        delay(ReaderEnhancement.CONFIG_CHANGE_DEBOUNCE_MS)
                        imagePropertyChangedListener?.invoke()
                    }
                }
                .launchIn(scope)
        }

        // 总开关变化时不重建列表（重建会让整页闪黑），改由各 page holder 自己平滑换图：
        // 开启时切到成品、关闭时切回原图
        cancelEnhancementOnChange(
            readerPreferences.realCuganEnabled().changes(),
            "realCuganEnabled changed",
            refreshImages = false,
        )
        cancelEnhancementOnChange(readerPreferences.realCuganModel().changes(), "realCuganModel changed")
        cancelEnhancementOnChange(readerPreferences.realEsrganStyle().changes(), "realEsrganStyle changed")
        cancelEnhancementOnChange(readerPreferences.realCuganNoiseLevel().changes(), "realCuganNoiseLevel changed")
        cancelEnhancementOnChange(readerPreferences.realCuganScale().changes(), "realCuganScale changed")
        cancelEnhancementOnChange(readerPreferences.realCuganPreloadSize().changes(), "realCuganPreloadSize changed")
        cancelEnhancementOnChange(readerPreferences.realCuganPerformanceMode().changes(), "realCuganPerformanceMode changed")
        cancelEnhancementOnChange(readerPreferences.realCuganTileSize().changes(), "realCuganTileSize changed")
        cancelEnhancementOnChange(readerPreferences.realCuganPrecision().changes(), "realCuganPrecision changed")
        cancelEnhancementOnChange(readerPreferences.realCuganProcessingBackend().changes(), "realCuganProcessingBackend changed")
        cancelEnhancementOnChange(readerPreferences.realCuganFp16Arithmetic().changes(), "realCuganFp16Arithmetic changed")
        cancelEnhancementOnChange(readerPreferences.realCuganMaxSizeWidth().changes(), "realCuganMaxSizeWidth changed")
        cancelEnhancementOnChange(readerPreferences.realCuganMaxSizeHeight().changes(), "realCuganMaxSizeHeight changed")
        cancelEnhancementOnChange(readerPreferences.realCuganSkipMaxSizeWidth().changes(), "realCuganSkipMaxSizeWidth changed")
        cancelEnhancementOnChange(readerPreferences.realCuganSkipMaxSizeHeight().changes(), "realCuganSkipMaxSizeHeight changed")
    }

    private fun zoomTypeFromPreference(value: Int) {
        imageZoomType = when (value) {
            // Auto
            1 -> when (viewer) {
                is L2RPagerViewer -> ReaderPageImageView.ZoomStartPosition.LEFT
                is R2LPagerViewer -> ReaderPageImageView.ZoomStartPosition.RIGHT
                else -> ReaderPageImageView.ZoomStartPosition.CENTER
            }
            // Left
            2 -> ReaderPageImageView.ZoomStartPosition.LEFT
            // Right
            3 -> ReaderPageImageView.ZoomStartPosition.RIGHT
            // Center
            else -> ReaderPageImageView.ZoomStartPosition.CENTER
        }
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return when (viewer) {
            is VerticalPagerViewer -> LNavigation()
            else -> RightAndLeftNavigation()
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            6 -> CustomNavigation(CustomTapZones.parse(customNavigation))
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }

    object CenterMarginType {
        const val NONE = 0
        const val DOUBLE_PAGE_CENTER_MARGIN = 1
        const val WIDE_PAGE_CENTER_MARGIN = 2
        const val DOUBLE_AND_WIDE_CENTER_MARGIN = 3
    }

    object PageLayout {
        const val SINGLE_PAGE = 0
        const val DOUBLE_PAGES = 1
        const val AUTOMATIC = 2
    }

    fun themeToColor(theme: Int) {
        pageCanvasColor = when (theme) {
            1 -> Color.BLACK
            2 -> 0x202125
            else -> Color.WHITE
        }
    }
}
