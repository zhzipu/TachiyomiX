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
const val ENGINE_SPAN_NOMOSUNI_4X = "span-nomosuni-4x"
const val ENGINE_HFA2K_SPAN = "hfa2k-span"
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

    /**
     * 走通用 ncnn 通道（[Waifu2x.initW2xEx]）的模型：engine 字符串 → 包内模型文件前缀 / halo / QNN context。
     *
     * 这类引擎没有专属的「内置原生初始化函数」，只吃模型包里的 `<stem>.param` + `<stem>.bin`，
     * 因此**新增同架构的模型**（换权重、换倍率）只需在模型包的 `modelpack.json` 里声明新的 engine 值、
     * 再在这里补一行即可，宿主其余代码不用动。
     *
     * ⚠️ 必须声明在 [builtIn] 之前：Kotlin 的 `object` 属性按书写顺序初始化，[builtIn] 的初始化器会读它。
     */
    private val ncnnModelSpecs: Map<String, NcnnModelSpec> = mapOf(
        // SPAN NomosUni 2x（带自己的 QNN context）
        ENGINE_SPAN_NOMOSUNI to NcnnModelSpec("2x-NomosUni-SPAN-multijpg-ldl", 24, "span-nomosuni-x2"),
        // SPAN NomosUni 4x：同架构同 halo，只是权重与倍率不同（宿主的 QNN context 只覆盖 2x，故不声明 NPU）
        ENGINE_SPAN_NOMOSUNI_4X to NcnnModelSpec("4x-NomosUni-SPAN-multijpg", 24),
        // HFA2k SPAN 2x（Omni Small 模型包实际带的权重——OmniSR 走不通 pnnx 通路，见 .workbuddy/enhance-packs/README.md）
        ENGINE_HFA2K_SPAN to NcnnModelSpec("2x-HFA2kSPAN", 24),
        // sudo UltraCompact
        ENGINE_SUDO_ULTRACOMPACT to NcnnModelSpec("2x-sudo-UltraCompact", 10),
        // ACNet（Anime4KCPP Net / ACNetGLSL 同源）
        ENGINE_ACNET to NcnnModelSpec("acnet", 10),
    )

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
    ) + ncnnModelSpecs.mapValues { (engine, spec) ->
        // 通用 ncnn 通道：模型目录由描述符解析，模型文件前缀 / padding / QNN context 见 [ncnnModelSpecs]
        BuiltInEngine(
            engine,
            initializer = { context, modelDir, _, settings ->
                Waifu2x.initW2xEx(
                    context,
                    modelDir,
                    modelStem = spec.stem,
                    scale = settings.scale,
                    qnnModelName = spec.qnnModelName,
                    padding = spec.padding,
                    tileSleepMs = settings.tileSleepMs,
                    tileSize = settings.tileSize,
                    precision = settings.precision,
                    fp16Arithmetic = settings.fp16Arithmetic,
                    processingBackend = settings.backend,
                )
            },
            processor = Waifu2x::processW2xEx,
        )
    }
}

/**
 * 一个通用 ncnn 模型的宿主侧参数。
 *
 * @param stem 包内模型文件前缀（`<stem>.param` / `<stem>.bin`）。
 * @param padding 推理时给每个 tile 四周预留的 halo 像素数（模型感受野决定；太小会在 tile 边界留下接缝）。
 * @param qnnModelName 宿主 `assets/qnn-contexts/` 里的 QNN context 名；没有对应 context 的模型留空 = 不支持 NPU。
 */
private data class NcnnModelSpec(
    val stem: String,
    val padding: Int,
    val qnnModelName: String? = null,
)

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
