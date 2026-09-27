package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.waifu2x.EnhancementConfig
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * 图像增强（AI 放大）的全部设置项，**不含**总开关。
 *
 * 自定义滤镜标签页（[ColorFilterPage]）与阅读器底栏的「增强设置」对话框
 * （见 `EnhancementSettingsDialog`）共用同一份实现，避免两处设置项漂移。
 */
@Composable
internal fun ColumnScope.EnhancementSettingsPage(screenModel: ReaderSettingsScreenModel) {
    val context = LocalContext.current
    // 模型包扫描需要读磁盘，进入本页时在后台刷新一次
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ModelPackManager.refresh(context.applicationContext) }
    }
    // 模型清单完全来自已安装模型包：未安装的模型包不会出现在这里
    val installedModels by ModelPackManager.models.collectAsState()
    val packOptions by ModelPackManager.packOptions.collectAsState()

    val modelKey by screenModel.preferences.realCuganModel().collectAsState()
    val realEsrganStyle by screenModel.preferences.realEsrganStyle().collectAsState()
    val realCuganNoiseLevel by screenModel.preferences.realCuganNoiseLevel().collectAsState()
    val realCuganScale by screenModel.preferences.realCuganScale().collectAsState()
    val realCuganPreloadSize by screenModel.preferences.realCuganPreloadSize().collectAsState()
    val processingBackend by screenModel.preferences.realCuganProcessingBackend().collectAsState()
    val npuDeviceAvailable = remember { Waifu2x.isQualcommNpuAvailable() }
    val useQualcommNpu = processingBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU && npuDeviceAvailable

    LaunchedEffect(npuDeviceAvailable, processingBackend) {
        if (!npuDeviceAvailable && processingBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU) {
            screenModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_VULKAN)
        }
    }

    // NPU 通路只列出描述符声明支持 NPU 的模型
    val candidateModels = remember(installedModels, useQualcommNpu) {
        if (useQualcommNpu) installedModels.filter { it.supportsNpu } else installedModels
    }
    val selectedModel = remember(candidateModels, modelKey) {
        candidateModels.firstOrNull { it.key == modelKey } ?: candidateModels.firstOrNull()
    }

    // 已选模型不可用时（模型包被卸载、或切到 NPU 后不再受支持）退回到第一个可用模型
    LaunchedEffect(candidateModels, selectedModel?.key, modelKey) {
        val fallback = selectedModel?.key
        if (fallback != null && fallback != modelKey) {
            screenModel.preferences.realCuganModel().set(fallback)
        }
    }

    val styleOptions = selectedModel?.styles.orEmpty()
    val allowedScales = selectedModel?.let { EnhancementConfig.allowedScales(it, realEsrganStyle) }.orEmpty()
    val denoiseOptions = selectedModel?.denoiseLevels.orEmpty()
    val precisionOptions = selectedModel?.let { EnhancementConfig.allowedPrecisions(it, useQualcommNpu) }.orEmpty()
    val scaleOptions = remember(allowedScales, selectedModel, useQualcommNpu) {
        if (!useQualcommNpu) {
            allowedScales
        } else {
            val npuScales = selectedModel?.npuScales.orEmpty()
            if (npuScales.isEmpty()) allowedScales else allowedScales.filter { it in npuScales }
        }
    }

    val precision by screenModel.preferences.realCuganPrecision().collectAsState()

    // 各选项收敛到描述符允许的取值，保证解码器读到的偏好一定合法
    LaunchedEffect(styleOptions, realEsrganStyle, selectedModel?.key) {
        if (styleOptions.isNotEmpty() && styleOptions.none { it.value == realEsrganStyle }) {
            val fallback = selectedModel?.let { EnhancementConfig.defaultStyle(it) } ?: styleOptions.first().value
            screenModel.preferences.realEsrganStyle().set(fallback)
        }
    }
    LaunchedEffect(scaleOptions, realCuganScale) {
        if (scaleOptions.isNotEmpty() && realCuganScale !in scaleOptions) {
            val model = selectedModel
            val preferred = model?.let { EnhancementConfig.effectiveScale(it, realCuganScale, realEsrganStyle) }
            screenModel.preferences.realCuganScale()
                .set(preferred?.takeIf { it in scaleOptions } ?: scaleOptions.first())
        }
    }
    LaunchedEffect(denoiseOptions, realCuganNoiseLevel, selectedModel?.key) {
        if (denoiseOptions.isNotEmpty() && realCuganNoiseLevel !in denoiseOptions) {
            val model = selectedModel
            val preferred = model?.let { EnhancementConfig.denoiseLevel(it, realCuganNoiseLevel) }
            screenModel.preferences.realCuganNoiseLevel()
                .set(preferred?.takeIf { it in denoiseOptions } ?: denoiseOptions.first())
        }
    }
    LaunchedEffect(precisionOptions, precision) {
        if (precisionOptions.isNotEmpty() && precision !in precisionOptions) {
            screenModel.preferences.realCuganPrecision().set(precisionOptions.first())
        }
    }

    // 包级可调项来自「当前选中模型所属模型包」的描述符，未声明时对应设置行不展示
    val options = selectedModel?.let { packOptions[it.packId] }

    SettingsChipRow(MR.strings.reader_processing_backend) {
        FilterChip(
            selected = processingBackend == Waifu2x.PROCESSING_BACKEND_VULKAN,
            onClick = {
                screenModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_VULKAN)
            },
            label = { Text(stringResource(MR.strings.reader_backend_vulkan)) },
        )
        FilterChip(
            selected = useQualcommNpu,
            onClick = {
                screenModel.preferences.realCuganProcessingBackend().set(Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU)
            },
            enabled = npuDeviceAvailable,
            label = { Text(stringResource(MR.strings.reader_backend_qualcomm_npu)) },
        )
        // Noval Ai 后端尚未实现：显示为灰色不可选中，点击只给出提示
        val backendContext = LocalContext.current
        Box(
            modifier = Modifier.clickable {
                backendContext.toast(MR.strings.enhancement_under_construction)
            },
        ) {
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text("Noval Ai") },
            )
        }
    }

    SettingsChipRow(MR.strings.reader_model) {
        // 只展示已安装模型包所提供的模型：装一个显示一个，未安装的不占位
        candidateModels.forEach { model ->
            FilterChip(
                selected = selectedModel?.key == model.key,
                onClick = { screenModel.preferences.realCuganModel().set(model.key) },
                label = { Text(model.name) },
            )
        }
    }

    if (candidateModels.isEmpty()) {
        EnhancementHintRow(stringResource(MR.strings.reader_model_pack_none_available))
        EnhancementHintRow(stringResource(MR.strings.reader_model_pack_manage_hint))
    }

    if (selectedModel != null && styleOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.reader_model_style) {
            styleOptions.forEach { style ->
                FilterChip(
                    selected = realEsrganStyle == style.value,
                    onClick = { screenModel.preferences.realEsrganStyle().set(style.value) },
                    label = { Text(style.name) },
                )
            }
        }
    }

    if (denoiseOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.reader_denoise_level) {
            denoiseOptions.forEach { level ->
                FilterChip(
                    selected = realCuganNoiseLevel == level,
                    onClick = { screenModel.preferences.realCuganNoiseLevel().set(level) },
                    label = { Text(denoiseLabel(level)) },
                )
            }
        }
    }

    if (selectedModel != null && scaleOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.reader_scale_factor) {
            if (scaleOptions.size == 1) {
                // 固定倍率的模型不提供倍率选择
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(MR.strings.reader_scale_fixed_value, scaleOptions.first())) },
                )
            } else {
                scaleOptions.forEach { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text(stringResource(MR.strings.reader_scale_factor_value, scale)) },
                    )
                }
            }
        }
    }

    val preloadPages = options?.preloadPages.orEmpty()
    if (preloadPages.isNotEmpty()) {
        val preloadContext = LocalContext.current
        val preloadScope = rememberCoroutineScope()
        val currentChapter by screenModel.currentChapterFlow.collectAsState()
        SettingsChipRow(MR.strings.reader_preload_pages) {
            preloadPages.forEach { size ->
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { screenModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(MR.strings.reader_preload_pages_value, size)) },
                )
            }
            // 「本章节」不修改预加载页数的设置，而是直接把本章所有页面加入增强队列
            FilterChip(
                selected = false,
                onClick = {
                    // 开启整章增强模式：后续入队的整章页面忽略「预加载页数」窗口限制
                    ImageEnhancer.setWholeChapterMode(true)
                    val chapter = currentChapter
                    val pages = chapter?.pages.orEmpty()
                    if (pages.isEmpty()) return@FilterChip
                    val mangaId = chapter?.chapter?.manga_id
                    val chapterId = chapter?.chapter?.id
                    preloadScope.launch {
                        withContext(Dispatchers.IO) {
                            // 整章页数超过缓存上限时，本章节缓存不再参与「满 100 张删最旧」的裁剪
                            if (mangaId != null && chapterId != null) {
                                ImageEnhancementCache.protectChapter(mangaId, chapterId, pages.size)
                            }
                            val loader = chapter?.pageLoader
                            if (loader is HttpPageLoader) {
                                // 在线章节：先把整章页面加入加载队列，加载完一页就自动排队增强一页
                                loader.loadWholeChapter()
                            } else {
                                // 本地/已下载章节：页面数据已就绪，直接排队增强
                                pages.forEach { ReaderEnhancement.request(preloadContext, it) }
                            }
                        }
                        preloadContext.toast(MR.strings.enhancement_chapter_queued)
                    }
                },
                label = { Text(stringResource(MR.strings.reader_preload_pages_chapter)) },
            )
        }
    }

    if (!useQualcommNpu) {
        val performanceModes = options?.gpuPerformanceModes.orEmpty()
        if (performanceModes.isNotEmpty()) {
            val performanceMode by screenModel.preferences.realCuganPerformanceMode().collectAsState()
            SettingsChipRow(MR.strings.reader_gpu_performance_mode) {
                performanceModes.forEach { value ->
                    FilterChip(
                        selected = performanceMode == value,
                        onClick = { screenModel.preferences.realCuganPerformanceMode().set(value) },
                        label = { Text(gpuPerformanceModeLabel(value)) },
                    )
                }
            }
        }

        val tileSizes = options?.tileSizes.orEmpty()
        if (tileSizes.isNotEmpty()) {
            val tileSize by screenModel.preferences.realCuganTileSize().collectAsState()
            SettingsChipRow(MR.strings.reader_tile_size) {
                tileSizes.forEach { value ->
                    FilterChip(
                        selected = tileSize == value,
                        onClick = { screenModel.preferences.realCuganTileSize().set(value) },
                        label = { Text(value.toString()) },
                    )
                }
            }
        }
    }

    if (precisionOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.reader_precision) {
            precisionOptions.forEach { value ->
                FilterChip(
                    selected = precision == value,
                    onClick = { screenModel.preferences.realCuganPrecision().set(value) },
                    label = { Text(precisionLabel(value)) },
                )
            }
        }
    }

    if (!useQualcommNpu && precision == 0 && options?.fp16Arithmetic == true) {
        CheckboxItem(
            label = stringResource(MR.strings.reader_fp16_arithmetic),
            pref = screenModel.preferences.realCuganFp16Arithmetic(),
        )
    }

    if (options?.maxProcessingResolution != null) {
        val processMaxWidth by screenModel.preferences.realCuganMaxSizeWidth().collectAsState()
        val processMaxHeight by screenModel.preferences.realCuganMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_processing_resolution),
            width = processMaxWidth,
            height = processMaxHeight,
            onWidthChange = { screenModel.preferences.realCuganMaxSizeWidth().set(it) },
            onHeightChange = { screenModel.preferences.realCuganMaxSizeHeight().set(it) },
        )
    }

    if (options?.maxResolution != null) {
        val skipMaxWidth by screenModel.preferences.realCuganSkipMaxSizeWidth().collectAsState()
        val skipMaxHeight by screenModel.preferences.realCuganSkipMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_max_resolution),
            width = skipMaxWidth,
            height = skipMaxHeight,
            onWidthChange = { screenModel.preferences.realCuganSkipMaxSizeWidth().set(it) },
            onHeightChange = { screenModel.preferences.realCuganSkipMaxSizeHeight().set(it) },
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.reader_show_processing_status),
        pref = screenModel.preferences.realCuganShowStatus(),
    )
}

/** 降噪档位标签：0=关闭，1..3=降噪 Nx，4=保守。 */
@Composable
private fun denoiseLabel(level: Int): String = when (level) {
    0 -> stringResource(MR.strings.reader_none)
    4 -> stringResource(MR.strings.reader_conservative)
    else -> stringResource(MR.strings.reader_denoise_level_value, level)
}

@Composable
private fun precisionLabel(precision: Int): String = when (precision) {
    0 -> stringResource(MR.strings.reader_precision_fp16)
    1 -> stringResource(MR.strings.reader_precision_fp32)
    2 -> stringResource(MR.strings.reader_precision_int8)
    3 -> stringResource(MR.strings.reader_precision_bf16)
    else -> precision.toString()
}

@Composable
private fun gpuPerformanceModeLabel(mode: Int): String = when (mode) {
    0 -> stringResource(MR.strings.reader_gpu_performance_high)
    1 -> stringResource(MR.strings.reader_gpu_performance_balanced)
    2 -> stringResource(MR.strings.reader_gpu_performance_power_saving)
    else -> mode.toString()
}

@Composable
private fun ColumnScope.EnhancementHintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                top = 0.dp,
                bottom = SettingsItemsPaddings.Vertical,
            ),
    )
}

@Composable
private fun ResolutionLimitFields(
    heading: String,
    width: Int,
    height: Int,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
) {
    Column {
        HeadingItem(heading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(MR.strings.reader_resolution_width),
            )
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = height,
                onValueChange = onHeightChange,
                label = stringResource(MR.strings.reader_resolution_height),
            )
        }
    }
}

@Composable
private fun ResolutionNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toResolutionText()) }

    LaunchedEffect(value) {
        val normalized = value.toResolutionText()
        if (text != normalized && (text.toIntOrNull() ?: 0) != value) {
            text = normalized
        }
    }

    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit)
            text = filtered
            onValueChange(filtered.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

private fun Int.toResolutionText(): String = if (this == 0) "" else toString()
