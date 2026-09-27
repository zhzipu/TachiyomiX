package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.modelpack.ModelPackModel

/** 内置推理引擎的 engine 标识。这些是算法实现的名字，不是模型目录。 */
const val ENGINE_WAIFU2X = "waifu2x"
const val ENGINE_WAIFU2X_UPCONV7 = "waifu2x-upconv7"
const val ENGINE_REALCUGAN_SE = "realcugan-se"
const val ENGINE_REALCUGAN_PRO = "realcugan-pro"
const val ENGINE_REALCUGAN_NOSE = "realcugan-nose"
const val ENGINE_REALESRGAN = "realesrgan"
const val ENGINE_SPAN_NOMOSUNI = "span-nomosuni"
const val ENGINE_SUDO_ULTRACOMPACT = "sudo-ultracompact"
const val ENGINE_ACNET = "acnet"

/** 一次推理的实际参数：由模型描述符 + 用户偏好换算得到。 */
data class EnhancementSettings(
    /** 降噪档位（无降噪选项的模型为 0）。 */
    val noise: Int,
    /** 实际输出倍率。 */
    val scale: Int,
    /** 实际精度：0=FP16、1=FP32、2=INT8、3=BF16。 */
    val precision: Int,
    val fp16Arithmetic: Boolean,
    val tileSleepMs: Int,
    val tileSize: Int,
    /** 0=Vulkan、1=Qualcomm NPU。 */
    val backend: Int,
    /** 模型风格值（无风格选项的模型为 0）。 */
    val style: Int,
)

/** 单个推理引擎：加载模型 + 处理位图。实现可以是内置原生库，也可以是模型包自带库。 */
interface EnhancementEngine {
    val id: String

    fun init(context: Context, model: ModelPackModel, settings: EnhancementSettings): Boolean

    fun process(input: Bitmap, pageIndex: Int): Bitmap?
}

/**
 * engine 字符串 → 推理引擎的派发入口。
 *
 * 优先使用模型包自带的 `libmodelpack.so`（见 [PackNativeEngines]），
 * 没有时回退到内置引擎；两者都没有时返回 null，调用方跳过本页增强并记录日志。
 */
object EnhancementEngines {
    fun engineFor(context: Context, model: ModelPackModel): EnhancementEngine? = try {
        val nativeEngine = PackNativeEngines.engineFor(context, model)
        android.util.Log.e(
            "EnhancementEngine",
            "engineFor model=${model.key} engine=${model.engine} " +
                "native=${nativeEngine != null} builtIn=${builtIn.containsKey(model.engine)}",
        )
        nativeEngine ?: builtIn[model.engine]
    } catch (t: Throwable) {
        android.util.Log.e("EnhancementEngine", "engineFor threw for ${model.key}", t)
        builtIn[model.engine]
    }

    /** 内置引擎表：engine 字符串 → 既有原生初始化/处理调用。 */
    private val builtIn: Map<String, EnhancementEngine> = mapOf(
        // waifu2x：noise0..noise3 与 scale2.0x 模型
        ENGINE_WAIFU2X to BuiltInEngine(
            ENGINE_WAIFU2X,
            initializer = { _, modelDir, _, settings ->
                Waifu2x.initWaifu2x(
                    modelDir,
                    settings.noise,
                    settings.scale,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                )
            },
            processor = Waifu2x::processWaifu2x,
        ),
        // waifu2x UpConv7：noise0..noise3_scale2.0x 命名
        ENGINE_WAIFU2X_UPCONV7 to BuiltInEngine(
            ENGINE_WAIFU2X_UPCONV7,
            initializer = { _, modelDir, _, settings ->
                Waifu2x.initWaifu2xUpconv7(
                    modelDir,
                    settings.noise,
                    settings.scale,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                )
            },
            processor = Waifu2x::processWaifu2x,
        ),
        // Real-CUGAN SE / Pro：同一实现，Pro 只换模型目录与可用档位（描述符声明）
        ENGINE_REALCUGAN_SE to BuiltInEngine(
            ENGINE_REALCUGAN_SE,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initRealCugan(
                    context,
                    modelDir,
                    settings.noise,
                    settings.scale,
                    isPro = false,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processRealCugan,
        ),
        ENGINE_REALCUGAN_PRO to BuiltInEngine(
            ENGINE_REALCUGAN_PRO,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initRealCugan(
                    context,
                    modelDir,
                    settings.noise,
                    settings.scale,
                    isPro = true,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processRealCugan,
        ),
        // Real-CUGAN Nose：只有 no-denoise 模型，无降噪/倍率选项
        ENGINE_REALCUGAN_NOSE to BuiltInEngine(
            ENGINE_REALCUGAN_NOSE,
            initializer = { _, modelDir, _, settings ->
                Waifu2x.initNose(
                    modelDir,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                )
            },
            processor = Waifu2x::processNose,
        ),
        // Real-ESRGAN：anime / photo 风格由描述符的 styles 决定模型目录与倍率
        ENGINE_REALESRGAN to BuiltInEngine(
            ENGINE_REALESRGAN,
            initializer = { context, modelDir, model, settings ->
                Waifu2x.initRealESRGAN(
                    context,
                    modelDir,
                    modelScale = EnhancementConfig.modelScale(model, settings.style, settings.scale),
                    outputScale = settings.scale,
                    style = settings.style,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processRealESRGAN,
        ),
        // SPAN NomosUni（通用 ncnn 模型，带自己的 QNN context）
        ENGINE_SPAN_NOMOSUNI to BuiltInEngine(
            ENGINE_SPAN_NOMOSUNI,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initW2xEx(
                    context,
                    modelDir,
                    modelStem = "2x-NomosUni-SPAN-multijpg-ldl",
                    scale = settings.scale,
                    qnnModelName = "span-nomosuni-x2",
                    padding = 24,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processW2xEx,
        ),
        // sudo UltraCompact
        ENGINE_SUDO_ULTRACOMPACT to BuiltInEngine(
            ENGINE_SUDO_ULTRACOMPACT,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initW2xEx(
                    context,
                    modelDir,
                    modelStem = "2x-sudo-UltraCompact",
                    scale = settings.scale,
                    qnnModelName = null,
                    padding = 10,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processW2xEx,
        ),
        // ACNet（Anime4KCPP Net / ACNetGLSL 同源）：2x 动漫超分 ncnn 模型，走通用 ncnn 入口
        ENGINE_ACNET to BuiltInEngine(
            ENGINE_ACNET,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initW2xEx(
                    context,
                    modelDir,
                    modelStem = "acnet",
                    scale = settings.scale,
                    qnnModelName = null,
                    padding = 10,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processW2xEx,
        ),
    )
}

/** 内置引擎：模型目录由描述符解析，剩下就是既有原生调用。 */
private const val TAG = "EnhancementEngine"

private class BuiltInEngine(
    override val id: String,
    private val initializer: (Context, String, ModelPackModel, EnhancementSettings) -> Boolean,
    private val processor: (Bitmap, Int) -> Bitmap?,
) : EnhancementEngine {
    override fun init(context: Context, model: ModelPackModel, settings: EnhancementSettings): Boolean {
        val assetPath = EnhancementConfig.assetPath(model, settings.style)
        val modelDir = ModelPackManager.findModelDirectory(context, model, assetPath)
        if (modelDir == null) {
            android.util.Log.w(TAG, "Engine $id: model directory '$assetPath' of ${model.key} is missing")
            return false
        }
        return initializer(context, modelDir, model, settings)
    }

    override fun process(input: Bitmap, pageIndex: Int): Bitmap? = processor(input, pageIndex)
}
