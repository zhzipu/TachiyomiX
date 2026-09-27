package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.util.qnn.QualcommHtp
import java.io.File

/**
 * Waifu2x image upscaler using ncnn.
 * Provides 2x upscaling with denoising for manga images.
 *
 * 本类只保留「引擎实现」：原生初始化、模型文件命名与性能参数。
 * 有哪些模型、模型叫什么、支持哪些倍率/降噪/精度，全部由外置模型包的描述符提供，
 * 见 `eu.kanade.tachiyomi.modelpack.ModelPackDescriptor` 与 [EnhancementEngines]。
 */
object Waifu2x {

    const val PROCESSING_BACKEND_VULKAN = 0
    const val PROCESSING_BACKEND_QUALCOMM_NPU = 1
    const val REAL_ESRGAN_STYLE_ANIME = 0
    const val REAL_ESRGAN_STYLE_PHOTO = 1

    private const val QNN_CONTEXT_CACHE_VERSION = "17"

    @Volatile private var isInitialized = false
    @Volatile private var isRealCuganInitialized = false
    @Volatile private var isRealEsrganInitialized = false
    @Volatile private var isNoseInitialized = false
    @Volatile private var isWaifu2xInitialized = false
    @Volatile private var isAnime4kInitialized = false
    @Volatile private var isW2xExInitialized = false

    init {
        try {
            System.loadLibrary("waifu2x-jni")
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available
        }
    }

    /**
     * 预加载 waifu2x 引擎的模型（阅读器开启图像增强时的预热路径）。
     * 模型目录来自模型包描述符中 engine 为 `waifu2x` 的模型。
     */
    fun init(context: Context, noiseLevel: Int = 2, scale: Int = 2): Boolean {
        if (isInitialized) return true

        return synchronized(this) {
            if (isInitialized) return true

            val model = ModelPackManager.installedModels(context)
                .firstOrNull { it.engine == ENGINE_WAIFU2X }
                ?: return false
            val modelDir = ModelPackManager.findModelDirectory(context, model) ?: return false

            isInitialized = nativeInit(modelDir, noiseLevel, scale, 0, false)
            if (isInitialized) {
                // Invalidate all other models
                isRealCuganInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false // Wait, I am Waifu2x (generic)
                isAnime4kInitialized = false
                isW2xExInitialized = false
            }
            isInitialized
        }
    }

    /**
     * Process a bitmap image with Waifu2x upscaling.
     *
     * @param input Input bitmap (will not be modified)
     * @return Upscaled bitmap, or null if processing failed
     */
    fun process(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isInitialized) return null

        // Ensure input is in ARGB_8888 format
        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        return nativeProcess(argbBitmap, id)
    }

    // Track current config to detect changes (excludes tileSleepMs since that doesn't require model reload)
    private data class RealCuganConfig(
        val noise: Int,
        val scale: Int,
        val isPro: Boolean,
        val precision: Int,
        val fp16Arithmetic: Boolean,
        val processingBackend: Int,
    )
    @Volatile private var lastRealCuganConfig: RealCuganConfig? = null

    fun initRealCugan(
        context: Context,
        modelDir: String,
        noiseLevel: Int,
        scale: Int,
        isPro: Boolean = false,
        tileSleepMs: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        processingBackend: Int = PROCESSING_BACKEND_VULKAN,
    ): Boolean {
        val effectiveNoiseLevel = if (isPro && noiseLevel !in setOf(0, 3, 4)) 3 else noiseLevel
        val newConfig = RealCuganConfig(
            effectiveNoiseLevel,
            scale,
            isPro,
            precision.coerceIn(0, 3),
            fp16Arithmetic,
            processingBackend,
        )

        // Fast path: if already initialized with same config, just update performance params and return
        if (isRealCuganInitialized && lastRealCuganConfig == newConfig) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        return synchronized(this) {
            val currentConfig = RealCuganConfig(
                effectiveNoiseLevel,
                scale,
                isPro,
                precision.coerceIn(0, 3),
                fp16Arithmetic,
                processingBackend,
            )

            // Force reinit only if model parameters changed (not tileSleepMs)
            if (lastRealCuganConfig != currentConfig) {
                android.util.Log.d("Waifu2x", "Config changed from $lastRealCuganConfig to $currentConfig, reinitializing...")
                isRealCuganInitialized = false
            }

            if (isRealCuganInitialized) {
                // Model already loaded, just update performance params
                nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
                return true
            }

            isRealCuganInitialized = nativeInitRealCugan(
                modelDir,
                effectiveNoiseLevel,
                scale,
                tileSleepMs,
                currentConfig.precision,
                currentConfig.fp16Arithmetic,
            )
            if (isRealCuganInitialized) {
                if (currentConfig.processingBackend == PROCESSING_BACKEND_QUALCOMM_NPU) {
                    val variant = when (effectiveNoiseLevel) {
                        1 -> "denoise1x"
                        2 -> "denoise2x"
                        3 -> "denoise3x"
                        4 -> "conservative"
                        else -> "no-denoise"
                    }
                    val precisionSuffix = if (currentConfig.precision == 2) "-int8" else ""
                    val family = if (isPro) "pro" else "se"
                    initializeQnnIfAvailable(
                        context,
                        "realcugan-$family-x$scale-$variant$precisionSuffix",
                        padding = if (scale == 3) 14 else 18,
                    )
                }
                lastRealCuganConfig = currentConfig
                nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

                // Invalidate all other models
                isInitialized = false
                isRealEsrganInitialized = false
                isNoseInitialized = false
                isWaifu2xInitialized = false
                isAnime4kInitialized = false
                isW2xExInitialized = false

                android.util.Log.d(
                    "Waifu2x",
                    "Initialized Real-CUGAN: isPro=$isPro, noise=$effectiveNoiseLevel, scale=$scale, " +
                        "tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${currentConfig.precision}, " +
                        "backend=${backendName(currentConfig.processingBackend)}",
                )
            }
            isRealCuganInitialized
        }
    }

    // Track Real-ESRGAN config
    private data class RealEsrganConfig(val style: Int, val scale: Int, val precision: Int, val fp16Arithmetic: Boolean, val processingBackend: Int)
    private var lastRealEsrganConfig: RealEsrganConfig? = null

    fun initRealESRGAN(
        context: Context,
        modelDir: String,
        modelScale: Int,
        outputScale: Int,
        style: Int = REAL_ESRGAN_STYLE_ANIME,
        tileSleepMs: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        processingBackend: Int = PROCESSING_BACKEND_VULKAN,
    ): Boolean = synchronized(this) {
        val isPhotoStyle = style == REAL_ESRGAN_STYLE_PHOTO
        val config = RealEsrganConfig(style, outputScale, precision.coerceIn(0, 3), fp16Arithmetic, processingBackend)
        // Force reinit if config changed
        if (lastRealEsrganConfig != config) {
            android.util.Log.d("Waifu2x", "Real-ESRGAN config changed from $lastRealEsrganConfig to $config, reinitializing...")
            isRealEsrganInitialized = false
        }

        if (isRealEsrganInitialized) {
            // Update throttling
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        isRealEsrganInitialized = nativeInitRealESRGAN(
            modelDir,
            modelScale,
            outputScale,
            config.precision,
            config.fp16Arithmetic,
        )
        if (isRealEsrganInitialized) {
            if (config.processingBackend == PROCESSING_BACKEND_QUALCOMM_NPU) {
                initializeQnnIfAvailable(
                    context,
                    if (isPhotoStyle) {
                        if (config.precision == 2) {
                            "realesrgan-general-x4v3-x2-int8"
                        } else {
                            "realesrgan-general-x4v3-x2"
                        }
                    } else {
                        if (config.precision == 2) {
                            "realesrgan-animevideov3-x2-int8"
                        } else {
                            "realesrgan-animevideov3-x2"
                        }
                    },
                    padding = if (isPhotoStyle) 32 else 16,
                )
            }
            lastRealEsrganConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false

            val variant = if (isPhotoStyle) "general-x4v3" else "animevideov3"
            android.util.Log.d("Waifu2x", "Initialized Real-ESRGAN $variant: outputScale=$outputScale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${config.precision}, backend=${backendName(config.processingBackend)}")
        }
        isRealEsrganInitialized
    }

    private data class GenericModelConfig(val precision: Int, val fp16Arithmetic: Boolean)
    private var lastNoseConfig: GenericModelConfig? = null

    fun initNose(modelDir: String, tileSleepMs: Int = 0, tileSize: Int = 128, precision: Int = 0, fp16Arithmetic: Boolean = false): Boolean = synchronized(this) {
        val config = GenericModelConfig(precision.coerceIn(0, 3), fp16Arithmetic)
        if (lastNoseConfig != config) {
            isNoseInitialized = false
        }
        if (isNoseInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        isNoseInitialized = nativeInitNose(modelDir, config.precision, config.fp16Arithmetic)
        if (isNoseInitialized) {
            lastNoseConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false

            android.util.Log.d("Waifu2x", "Initialized Nose model, tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${config.precision}")
        }
        isNoseInitialized
    }

    // Track Waifu2x config
    private data class Waifu2xConfig(val noise: Int, val scale: Int, val precision: Int, val fp16Arithmetic: Boolean)
    private var lastWaifu2xConfig: Waifu2xConfig? = null

    fun initWaifu2x(modelDir: String, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128, precision: Int = 0, fp16Arithmetic: Boolean = false): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale, precision.coerceIn(0, 3), fp16Arithmetic)

        // Force reinit if config changed
        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Waifu2x config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        isWaifu2xInitialized = nativeInit(modelDir, noise, scale, newConfig.precision, newConfig.fp16Arithmetic)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false

            android.util.Log.d("Waifu2x", "Initialized Waifu2x: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    fun initWaifu2xUpconv7(modelDir: String, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = 128, precision: Int = 0, fp16Arithmetic: Boolean = false): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(noise, scale, precision.coerceIn(0, 3), fp16Arithmetic)

        // Force reinit if config changed
        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Waifu2x UpConv7 config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        isWaifu2xInitialized = nativeInitWaifu2xUpconv7(modelDir, noise, scale, newConfig.precision, newConfig.fp16Arithmetic)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            // Invalidate all other models
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false

            android.util.Log.d("Waifu2x", "Initialized Waifu2x UpConv7: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    private data class W2xExConfig(
        val stem: String,
        val scale: Int,
        val precision: Int,
        val fp16Arithmetic: Boolean,
        val processingBackend: Int,
    )
    private var lastW2xExConfig: W2xExConfig? = null

    /**
     * 通用 ncnn 模型（SPAN nomosuni / sudo UltraCompact 等）。
     * 模型目录、模型文件前缀、padding 与 QNN context 名由内置引擎表按 engine 字符串给出。
     */
    fun initW2xEx(
        context: Context,
        modelDir: String,
        modelStem: String,
        scale: Int = 2,
        qnnModelName: String? = null,
        padding: Int = 10,
        tileSleepMs: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        processingBackend: Int = PROCESSING_BACKEND_VULKAN,
    ): Boolean = synchronized(this) {
        val config = W2xExConfig(modelStem, scale, precision.coerceIn(0, 3), fp16Arithmetic, processingBackend)
        if (lastW2xExConfig != config) {
            isW2xExInitialized = false
        }

        if (isW2xExInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        isW2xExInitialized = nativeInitW2xEx(
            modelDir,
            modelStem,
            scale,
            config.precision,
            config.fp16Arithmetic,
            padding,
        )
        if (isW2xExInitialized) {
            if (config.processingBackend == PROCESSING_BACKEND_QUALCOMM_NPU && qnnModelName != null) {
                initializeQnnIfAvailable(
                    context,
                    if (config.precision == 2) "$qnnModelName-int8" else qnnModelName,
                    padding = padding,
                )
            }
            lastW2xExConfig = config
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false

            android.util.Log.d(
                "Waifu2x",
                "Initialized generic ncnn model: $modelStem, scale=$scale, precision=${config.precision}, backend=${backendName(config.processingBackend)}",
            )
        }
        isW2xExInitialized
    }

    // Reuse processRealCugan for all generic ncnn models
    // But check specific flags
    // Reuse processRealCugan for all generic ncnn models
    // But check specific flags
    fun processRealESRGAN(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isRealEsrganInitialized) return null
        return processBitmapHelper(input, id)
    }

    fun processNose(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isNoseInitialized) return null
        return processBitmapHelper(input, id)
    }

    fun processWaifu2x(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isWaifu2xInitialized) return null
        return processBitmapHelper(input, id)
    }

    fun processW2xEx(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isW2xExInitialized) return null
        return processBitmapHelper(input, id)
    }

    @Volatile var processingId: Int = -1

    private fun processBitmapHelper(input: Bitmap, id: Int): Bitmap? {
        if (input.isRecycled) return null

        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        processingId = id
        try {
            val result = nativeProcessRealCugan(argbBitmap, id)
            return if (result === argbBitmap) null else result
        } finally {
            processingId = -1
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    /**
     * Get the raw packed progress value from native code.
     * Format: [ID (upper 32 bits)] [Progress (lower 32 bits)]
     */
    fun getProgress(): Long = nativeGetProgress()

    /**
     * Get only the progress percentage (0-100) from the packed value.
     */
    fun getProgressPercent(): Int {
        val packed = nativeGetProgress()
        return (packed and 0xFFFFFFFF).toInt()
    }

    /**
     * Get only the processing ID from the packed value.
     */
    fun getProgressId(): Int {
        val packed = nativeGetProgress()
        return (packed shr 32).toInt()
    }

    /**
     * Reset Real-CUGAN to allow re-initialization with new settings.
     */
    fun resetRealCugan() {
        isInitialized = false
        isRealCuganInitialized = false
        isRealEsrganInitialized = false
        isNoseInitialized = false
        isWaifu2xInitialized = false
        isAnime4kInitialized = false
        isW2xExInitialized = false
        lastRealCuganConfig = null
        lastRealEsrganConfig = null
        lastNoseConfig = null
        lastWaifu2xConfig = null
        lastW2xExConfig = null
        nativeDestroy()
    }

    /**
     * Ask any active native upscaling operation to stop at its next cancellation check.
     */
    fun abortProcessing() {
        nativeAbortProcessing()
    }

    fun prepareProcessing() {
        nativeClearAbortProcessing()
    }

    /**
     * Process bitmap with Real-CUGAN.
     */
    fun processRealCugan(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isRealCuganInitialized) return null
        return processBitmapHelper(input, id)
    }

    /**
     * Release native resources.
     */
    fun destroy() {
        if (isInitialized || isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized || isAnime4kInitialized || isW2xExInitialized) {
            nativeDestroy()
            isInitialized = false
            isRealCuganInitialized = false
            isRealEsrganInitialized = false
            isNoseInitialized = false
            isWaifu2xInitialized = false
            isAnime4kInitialized = false
            isW2xExInitialized = false
        }
    }

    /**
     * Initialize Anime4K with specific mode.
     */
    fun initAnime4K(context: Context, mode: Int): Boolean {
        if (isAnime4kInitialized) return true

        val assetManager = context.assets
        val shaders = mutableListOf<String>()
        val names = mutableListOf<String>()

        fun addShader(name: String) {
            val content = assetManager.open("anime4k/$name").bufferedReader().use { it.readText() }
            shaders.add(content)
            names.add(name)
        }

        try {
            addShader("Anime4K_Clamp_Highlights.glsl")
            when (mode) {
                0 -> addShader("Anime4K_Restore_CNN_M.glsl") // Fast
                1 -> addShader("Anime4K_Restore_CNN_VL.glsl") // High
                2 -> { // Ultra
                    addShader("Anime4K_Restore_CNN_VL.glsl")
                    addShader("Anime4K_Upscale_CNN_x2_VL.glsl")
                }
            }
        } catch (e: Exception) {
            return false
        }

        isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
        // Invalidate all other models
        if (isAnime4kInitialized) {
             isInitialized = false
             isRealCuganInitialized = false
             isRealEsrganInitialized = false
             isNoseInitialized = false
             isWaifu2xInitialized = false
             isW2xExInitialized = false
        }
        return isAnime4kInitialized
    }

    /**
     * Process bitmap with Anime4K.
     */
    fun processAnime4K(input: Bitmap): Bitmap? {
        if (!isAnime4kInitialized || input.isRecycled) return null

        val argbBitmap = try {
            if (input.config != Bitmap.Config.ARGB_8888) {
                input.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                input.copy(Bitmap.Config.ARGB_8888, true) // Must be mutable for in-place
            }
        } catch (e: Exception) {
            null
        } ?: return null

        try {
            return nativeProcessAnime4K(argbBitmap)
        } finally {
            // We don't recycle argbBitmap if it's the same as input,
            // but here it's always a copy (true).
            // Actually, nativeProcessAnime4K returns the SAME bitmap (in-place)
            // so we SHOULD NOT recycle it here if it's the result.
        }
    }


    /**
     * 解析模型所在目录（引擎预热路径）。
     *
     * 模型只通过外置「模型包」提供，不再内置进 APK，也不联网下载；
     * 未安装对应的模型包时返回 null，上层据此提示用户安装。
     */
    private fun extractModelsToCache(context: Context, assetPath: String): String? {
        // 模型包安装后会把 assets 解压到 filesDir/model-packs/<id>/，
        // 路径与原先内置 assets 保持一致，因此可直接按 assetPath 命中。
        return ModelPackManager.findModelDirectory(context, assetPath)
    }

    fun setUiBusy(busy: Boolean) {
        nativeSetUiBusy(busy)
    }

    fun isQnnRuntimeAvailable(): Boolean = try {
        nativeIsQnnRuntimeAvailable()
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isQualcommNpuAvailable(): Boolean = QualcommHtp.architecture() != null && isQnnRuntimeAvailable()

    private fun backendName(backend: Int): String {
        return if (backend == PROCESSING_BACKEND_QUALCOMM_NPU) "Qualcomm NPU" else "Vulkan"
    }

    fun isQnnActive(): Boolean = try {
        nativeIsQnnInitialized()
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    private fun initializeQnnIfAvailable(context: Context, modelName: String, padding: Int) {
        val htpArchitecture = QualcommHtp.architecture(context) ?: return
        if (!isQnnRuntimeAvailable()) return
        try {
            val filename = "$modelName.v$htpArchitecture.bin"
            val directory = File(context.cacheDir, "qnn-contexts-v$htpArchitecture").apply { mkdirs() }
            val output = File(directory, filename)
            val version = File(directory, ".$filename.version")
            if (!output.isFile || version.takeIf(File::isFile)?.readText() != QNN_CONTEXT_CACHE_VERSION) {
                context.assets.open("qnn-contexts/$filename").use { input ->
                    output.outputStream().use(input::copyTo)
                }
                version.writeText(QNN_CONTEXT_CACHE_VERSION)
            }
            val active = nativeInitQnn(
                output.absolutePath,
                context.applicationInfo.nativeLibraryDir,
                padding,
            )
            android.util.Log.d(
                "Waifu2x",
                "Qualcomm NPU ${if (active) "enabled" else "unavailable"}: $filename (HTP v$htpArchitecture)",
            )
        } catch (e: Exception) {
            android.util.Log.w("Waifu2x", "Unable to initialize Qualcomm NPU; using Vulkan", e)
        }
    }

    fun scaleBitmapNative(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (input.isRecycled) return null
        if (input.width == targetWidth && input.height == targetHeight) return input

        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        return try {
            nativeScaleBitmap(argbBitmap, targetWidth, targetHeight)
        } finally {
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    // Native methods
    private external fun nativeInit(modelDir: String, noiseLevel: Int, scale: Int, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeInitWaifu2xUpconv7(modelDir: String, noiseLevel: Int, scale: Int, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeInitW2xEx(
        modelDir: String,
        modelStem: String,
        scale: Int,
        precision: Int,
        fp16Arithmetic: Boolean,
        padding: Int,
    ): Boolean
    private external fun nativeProcess(input: Bitmap, id: Int): Bitmap?
    private external fun nativeDestroy()
    private external fun nativeAbortProcessing()
    private external fun nativeClearAbortProcessing()
    private external fun nativeSetUiBusy(busy: Boolean)
    private external fun nativeIsQnnRuntimeAvailable(): Boolean
    private external fun nativeInitQnn(contextPath: String, nativeLibraryDir: String, padding: Int): Boolean
    private external fun nativeIsQnnInitialized(): Boolean

    // ... (Anime4K signatures unchanged)

    private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    private external fun nativeProcessAnime4K(input: Bitmap): Bitmap?

    private external fun nativeInitRealCugan(modelDir: String, noiseLevel: Int, scale: Int, tileSleepMs: Int, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int)

    fun updatePerformance(tileSleepMs: Int, tileSize: Int) {
        if (isRealCuganInitialized || isRealEsrganInitialized || isNoseInitialized || isWaifu2xInitialized || isW2xExInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
        }
    }

    private external fun nativeInitRealESRGAN(modelDir: String, modelScale: Int, outputScale: Int, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeInitNose(modelDir: String, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeProcessRealCugan(input: Bitmap, id: Int): Bitmap?
    private external fun nativeScaleBitmap(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap?
    private external fun nativeGetProgress(): Long
}
