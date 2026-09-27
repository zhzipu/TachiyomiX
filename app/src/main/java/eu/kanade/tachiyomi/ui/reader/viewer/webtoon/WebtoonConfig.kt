package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Configuration used by webtoon viewers.
 */
class WebtoonConfig(
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ViewerConfig(readerPreferences, scope) {

    var themeChangedListener: (() -> Unit)? = null

    var imageCropBorders = false
        private set

    var zoomOutDisabled = false
        private set

    var zoomPropertyChangedListener: ((Boolean) -> Unit)? = null

    var sidePadding = 0
        private set

    var doubleTapZoom = true
        private set

    var doubleTapZoomChangedListener: ((Boolean) -> Unit)? = null

    val theme = readerPreferences.readerTheme.get()

    // SY -->
    var usePageTransitions = false

    var continuousCropBorders = false
        private set

    // SY <--

    var customNavigation = readerPreferences.customNavigationWebtoon.get()
        private set

    init {
        readerPreferences.cropBordersWebtoon
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.webtoonSidePadding
            .register({ sidePadding = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModeWebtoon
            .register({ navigationMode = it }, { updateNavigation(it) })

        readerPreferences.webtoonNavInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.webtoonNavInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        // SY -->
        readerPreferences.customNavigationWebtoon.changes()
            .drop(1)
            .onEach { value ->
                customNavigation = value
                if (navigationMode == 6) updateNavigation(navigationMode)
            }
            .launchIn(scope)
        // SY <--

        readerPreferences.dualPageSplitWebtoon
            .register({ dualPageSplit = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageInvertWebtoon
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFitWebtoon
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.dualPageRotateToFitInvertWebtoon
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.webtoonDisableZoomOut
            .register(
                { zoomOutDisabled = it },
                { zoomPropertyChangedListener?.invoke(it) },
            )

        readerPreferences.webtoonDoubleTapZoomEnabled
            .register(
                { doubleTapZoom = it && !readerPreferences.disableDoubleTapZoom.get() },
                { doubleTapZoomChangedListener?.invoke(doubleTapZoom) },
            )

        // 常规里的「禁止双击缩放」是全局开关，与条漫自己的「双击缩放」是「与」的关系
        readerPreferences.disableDoubleTapZoom
            .register(
                { doubleTapZoom = readerPreferences.webtoonDoubleTapZoomEnabled.get() && !it },
                { doubleTapZoomChangedListener?.invoke(doubleTapZoom) },
            )

        readerPreferences.readerTheme.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { themeChangedListener?.invoke() }
            .launchIn(scope)

        // SY -->
        readerPreferences.cropBordersContinuousVertical
            .register({ continuousCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.pageTransitionsWebtoon
            .register({ usePageTransitions = it }, { imagePropertyChangedListener?.invoke() })
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

        // 总开关变化时无论开还是关都要刷新：关掉后画面需要回到原图
        cancelEnhancementOnChange(
            readerPreferences.realCuganEnabled().changes(),
            "realCuganEnabled changed",
            refreshOnlyWhenEnabled = false,
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

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return LNavigation()
    }

    override fun updateNavigation(navigationMode: Int) {
        this.navigator = when (navigationMode) {
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
}
