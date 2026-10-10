package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.CustomTapZones
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR

class ReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region General

    // SY -->
    val pageTransitionsPager: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_pager_key", true)

    val pageTransitionsWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_webtoon_key", true)
    // SY <--

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val showSystemTime: Preference<Boolean> = preferenceStore.getBoolean("pref_show_system_time_key", true)

    // SY -->
    /**
     * 顶部/底部指示器的纵向偏移（dp，负值向上）。
     *
     * 默认 0 = 沿用原本位置（页码贴底、时间电量贴顶），所以新增这两个设置不改变老用户看到的样子。
     */
    val pageIndicatorYOffset: Preference<Int> = preferenceStore.getInt("pref_page_indicator_y_offset", 0)
    val systemTimeYOffset: Preference<Int> = preferenceStore.getInt("pref_system_time_y_offset", 0)
    // SY <--

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    /** 全局禁止双击缩放：对单页式（含动图）与条漫同时生效，只影响双击，不影响双指缩放。 */
    val disableDoubleTapZoom: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_disable_double_tap_zoom",
        false,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // region 图像增强（AI 放大）
    // 说明：以下键名、类型与默认值均与参考项目 mihon_img_upscale 保持一致

    fun waifu2xEnabled() = preferenceStore.getBoolean("pref_waifu2x_enabled", false)

    fun waifu2xNoiseLevel() = preferenceStore.getInt("pref_waifu2x_noise_level", 2)

    fun anime4kEnabled() = preferenceStore.getBoolean("pref_anime4k_enabled", false)

    fun anime4kMode() = preferenceStore.getInt("pref_anime4k_mode", 0) // 0: Fast, 1: High, 2: Ultra

    fun realCuganEnabled() = preferenceStore.getBoolean("pref_realcugan_enabled", false)

    fun realCuganNoiseLevel() = preferenceStore.getInt("pref_realcugan_noise_level", 0) // 0: No Denoise, 1: Denoise 1x, 2: Denoise 2x, 3: Denoise 3x, 4: Conservative

    fun realCuganScale() = preferenceStore.getInt("pref_realcugan_scale", 2) // 2x, 3x, 4x

    // 选中的模型用模型包描述符里的 key（字符串）持久化。
    // 键名使用 *_key 变体：旧版本在同名键上存的是 Int，SharedPreferences 读取类型不一致会抛异常。
    fun realCuganModel() = preferenceStore.getString("pref_realcugan_model_key", "")

    fun realEsrganStyle() = preferenceStore.getInt("pref_realesrgan_style", 0) // 0: Anime, 1: Photo

    fun realCuganPreloadSize() = preferenceStore.getInt("pref_realcugan_preload_size", 3)

    fun realCuganProEnabled() = preferenceStore.getBoolean("pref_realcugan_pro_enabled", false)

    fun realCuganPerformanceMode() = preferenceStore.getInt("pref_realcugan_performance_mode", 0) // 0: 90%, 1: 50%, 2: 30%

    fun realCuganTileSize() = preferenceStore.getInt("pref_realcugan_tile_size", 128)

    fun realCuganPrecision() = preferenceStore.getInt("pref_realcugan_precision", 0) // 0: FP16, 1: FP32, 2: INT8, 3: BF16

    // 0: Vulkan, 1: Qualcomm NPU
    // 默认使用 Vulkan：NPU 通路需要额外的 QNN 预编译 context（qnn-contexts/*.bin），本仓库暂未提供，
    // 若默认走 NPU 会导致增强初始化失败、页面回退原图。
    fun realCuganProcessingBackend() = preferenceStore.getInt("pref_realcugan_processing_backend", 0)

    fun realCuganFp16Arithmetic() = preferenceStore.getBoolean("pref_realcugan_fp16_arithmetic", false)

    fun realCuganMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_max_size_width", 1600)

    fun realCuganMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_max_size_height", 1600)

    fun realCuganSkipMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_skip_max_size_width", 0)

    fun realCuganSkipMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_skip_max_size_height", 0)

    fun realCuganShowStatus() = preferenceStore.getBoolean("pref_realcugan_show_status", true)

    // endregion

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    // SY -->
    val customNavigationPager: Preference<String> = preferenceStore.getString(
        "reader_navigation_custom_pager",
        CustomTapZones.serialize(CustomTapZones.pagerDefault),
    )

    val customNavigationWebtoon: Preference<String> = preferenceStore.getString(
        "reader_navigation_custom_webtoon",
        CustomTapZones.serialize(CustomTapZones.webtoonDefault),
    )
    // SY <--

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // SY -->

    val readerThreads: Preference<Int> = preferenceStore.getInt("eh_reader_threads", 2)

    val readerInstantRetry: Preference<Boolean> = preferenceStore.getBoolean("eh_reader_instant_retry", true)

    val aggressivePageLoading: Preference<Boolean> = preferenceStore.getBoolean("eh_aggressive_page_loading", false)

    val cacheSize: Preference<String> = preferenceStore.getString("eh_cache_size", "75")

    val autoscrollInterval: Preference<Float> = preferenceStore.getFloat("eh_util_autoscroll_interval", 3f)

    val smoothAutoScroll: Preference<Boolean> = preferenceStore.getBoolean("smooth_auto_scroll", true)

    val preserveReadingPosition: Preference<Boolean> = preferenceStore.getBoolean("eh_preserve_reading_position", false)

    val preloadSize: Preference<Int> = preferenceStore.getInt("eh_preload_size", 10)

    val useAutoWebtoon: Preference<Boolean> = preferenceStore.getBoolean("eh_use_auto_webtoon", true)

    val continuousVerticalTappingByPage: Preference<Boolean> = preferenceStore.getBoolean("continuous_vertical_tapping_by_page", false)

    val cropBordersContinuousVertical: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_continues_vertical", false)

    val readerBottomButtons: Preference<Set<String>> = preferenceStore.getStringSet("reader_bottom_buttons", ReaderBottomButton.BUTTONS_DEFAULTS)

    val pageLayout: Preference<Int> = preferenceStore.getInt("page_layout", PagerConfig.PageLayout.AUTOMATIC)

    val centerMarginType: Preference<Int> = preferenceStore.getInt("center_margin_type", PagerConfig.CenterMarginType.NONE)

    val archiveReaderMode: Preference<Int> = preferenceStore.getInt("archive_reader_mode", ArchiveReaderMode.LOAD_FROM_FILE)
    // SY <--

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(MR.strings.tapping_inverted_horizontal, shouldInvertHorizontal = true),
        VERTICAL(MR.strings.tapping_inverted_vertical, shouldInvertVertical = true),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    object ArchiveReaderMode {
        const val LOAD_FROM_FILE = 0
        const val LOAD_INTO_MEMORY = 1
        const val CACHE_TO_DISK = 2
    }

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
            MR.strings.nav_custom,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }

        // SY -->
        val PageLayouts = listOf(
            SYMR.strings.single_page,
            SYMR.strings.double_pages,
            SYMR.strings.automatic_orientation,
        )

        val CenterMarginTypes = listOf(
            SYMR.strings.center_margin_none,
            SYMR.strings.center_margin_double_page,
            SYMR.strings.center_margin_wide_page,
            SYMR.strings.center_margin_double_and_wide_page,
        )

        val archiveModeTypes = listOf(
            SYMR.strings.archive_mode_load_from_file,
            SYMR.strings.archive_mode_load_into_memory,
            SYMR.strings.archive_mode_cache_to_disk,
        )
        // SY <--
    }
}
