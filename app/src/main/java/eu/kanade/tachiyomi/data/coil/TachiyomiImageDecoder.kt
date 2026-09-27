package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.CbzCrypto.getCoverStream
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.waifu2x.EnhancementConfig
import eu.kanade.tachiyomi.util.waifu2x.EnhancementEngines
import eu.kanade.tachiyomi.util.waifu2x.EnhancementSettings
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.core.common.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult {
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                ImageDecoder.newInstance(coverStream ?: it.inputStream(), options.cropBorders, displayProfile)
            }
        }
        // SY <--

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        // 图像增强（AI 放大）：只有 ImageEnhancer 发起的处理请求（options.enhanced）会进入这里，
        // 阅读器显示请求不带该标记，因此未开启增强时解码路径与性能与之前完全一致。
        if (options.enhanced) {
            enhanceToCache(bitmap)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    /**
     * 图像增强（AI 放大）：按当前阅读器偏好初始化对应原生模型，把 [source] 放大后写入增强缓存。
     *
     * 仅由 ImageEnhancer 通过 Coil 发起的处理请求（[Options.enhanced] 为 true）触发，
     * 放大结果经 [ImageEnhancementCache] 落盘，阅读器显示时优先读取该缓存文件。
     * 本方法不回收到 [source]（其所有权归调用方），只回收自己产生的中间 bitmap。
     */
    private suspend fun enhanceToCache(source: Bitmap) {
        val mangaId = options.mangaId
        val chapterId = options.chapterId
        val pageIndex = options.pageIndex
        val pageVariant = options.pageVariant
        if (mangaId == -1L || chapterId == -1L || pageIndex == -1) return

        val preferences = Injekt.get<ReaderPreferences>()
        if (!preferences.realCuganEnabled().get()) return

        ImageEnhancementCache.init(context)
        val configHash = ReaderEnhancement.configHash(preferences)
        if (ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant) != null) {
            // 同一配置下已经生成过，无需重复放大
            return
        }

        val modelKey = preferences.realCuganModel().get()
        // 模型完全来自已安装模型包：未安装对应模型包时不做增强
        val model = ModelPackManager.findModel(context, modelKey)
        if (model == null) {
            logcat(LogPriority.DEBUG) {
                "TachiyomiImageDecoder: 第 $pageIndex 页未找到模型 $modelKey（模型包未安装），不做增强"
            }
            return
        }
        val realEsrganStyle = preferences.realEsrganStyle().get()
        val noise = preferences.realCuganNoiseLevel().get()
        val scale = preferences.realCuganScale().get()

        // 源图超过跳过阈值时不做处理，仅记录跳过标记，避免超大长图拖垮设备
        val skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get()
        val skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get()
        if ((skipMaxWidth > 0 && source.width > skipMaxWidth) ||
            (skipMaxHeight > 0 && source.height > skipMaxHeight)
        ) {
            logcat(LogPriority.DEBUG) {
                "TachiyomiImageDecoder: 第 $pageIndex 页源图 ${source.width}x${source.height} 超过跳过阈值，不做增强"
            }
            ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
            return
        }

        // 处理分辨率上限：超出时先按比例缩小输入，控制内存与显存占用
        var prescaled: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()

            val processMaxWidth = preferences.realCuganMaxSizeWidth().get()
            val processMaxHeight = preferences.realCuganMaxSizeHeight().get()
            val widthRatio = if (processMaxWidth > 0) processMaxWidth / source.width.toFloat() else Float.POSITIVE_INFINITY
            val heightRatio = if (processMaxHeight > 0) processMaxHeight / source.height.toFloat() else Float.POSITIVE_INFINITY
            val ratio = min(widthRatio, heightRatio)
            prescaled = if (ratio in 0f..<1f) {
                nativeScaleBitmap(
                    source,
                    max(1, (source.width * ratio).roundToInt()),
                    max(1, (source.height * ratio).roundToInt()),
                ).takeIf { it !== source }
            } else {
                null
            }
            val input = prescaled ?: source

            val perfMode = preferences.realCuganPerformanceMode().get()
            val tileSleepMs = when (perfMode) {
                1 -> 5
                2 -> 15
                else -> 0
            }
            val tileSize = preferences.realCuganTileSize().get().coerceAtLeast(32)
            val precision = preferences.realCuganPrecision().get().coerceIn(0, 3)
            val fp16Arithmetic = preferences.realCuganFp16Arithmetic().get()
            // 倍率/降噪/后端/精度全部按模型包描述符声明的能力收敛
            val effectiveScale = EnhancementConfig.effectiveScale(model, scale, realEsrganStyle)
            val processingBackend = EnhancementConfig.resolveBackend(
                model,
                preferences.realCuganProcessingBackend().get(),
                effectiveScale,
            )
            val resolvedPrecision = EnhancementConfig.resolvePrecision(
                model,
                precision,
                processingBackend,
                effectiveScale,
            )

            // engine 字符串 → 推理实现；模型包自带 libmodelpack.so 时优先用包内实现
            val engine = EnhancementEngines.engineFor(context, model)
            if (engine == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiImageDecoder: 第 $pageIndex 页的模型 ${model.key} 使用未知引擎 ${model.engine}，跳过增强"
                }
                return
            }

            val settings = EnhancementSettings(
                noise = EnhancementConfig.denoiseLevel(model, noise),
                scale = effectiveScale,
                precision = resolvedPrecision,
                fp16Arithmetic = fp16Arithmetic,
                tileSleepMs = tileSleepMs,
                tileSize = tileSize,
                backend = processingBackend,
                style = realEsrganStyle,
            )

            val initialized = engine.init(context, model, settings)
            val processed = if (initialized) {
                engine.process(input, pageIndex)
            } else {
                null
            }

            if (processed == null) {
                logcat(LogPriority.WARN) { "TachiyomiImageDecoder: 第 $pageIndex 页图像增强失败（模型未初始化或处理失败）" }
                return
            }

            var result = processed
            var ownedByCache = false
            try {
                currentCoroutineContext().ensureActive()

                // 输出分辨率上限：超过设备纹理限制时按比例缩小，避免 Canvas 报错
                val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                if (result.width > textureLimit || result.height > textureLimit) {
                    val limitRatio = min(textureLimit.toFloat() / result.width, textureLimit.toFloat() / result.height)
                    logcat(LogPriority.DEBUG) {
                        "TachiyomiImageDecoder: 第 $pageIndex 页结果 ${result.width}x${result.height} 超过纹理上限 $textureLimit，按比例缩小"
                    }
                    val downscaled = nativeScaleBitmap(
                        result,
                        (result.width * limitRatio).toInt().coerceAtLeast(1),
                        (result.height * limitRatio).toInt().coerceAtLeast(1),
                    )
                    if (downscaled !== result) {
                        result.recycle()
                        result = downscaled
                    }
                }

                if (ImageEnhancementCache.isDisplayable(result)) {
                    // enqueueSaveToCache 会接管 bitmap 所有权（即使被拒绝也会自行回收）
                    ownedByCache = true
                    ImageEnhancementCache.enqueueSaveToCache(mangaId, chapterId, pageIndex, configHash, result, pageVariant)
                } else {
                    logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: 第 $pageIndex 页增强结果接近全透明，丢弃" }
                }
            } finally {
                if (!ownedByCache && !result.isRecycled) result.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 原生库不可用或处理失败时只放弃本次增强，页面回退为原图
            logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: 第 $pageIndex 页图像增强失败" }
        } finally {
            if (prescaled != null && !prescaled.isRecycled) prescaled.recycle()
        }
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL -> true
                ImageUtil.ImageType.HEIF -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}

/**
 * 图像增强使用的缩放：优先走原生缩放以保证像素质量，失败时回退到系统缩放。
 */
private fun nativeScaleBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    return Waifu2x.scaleBitmapNative(source, max(1, targetWidth), max(1, targetHeight))
        ?: Bitmap.createScaledBitmap(source, max(1, targetWidth), max(1, targetHeight), true)
}
