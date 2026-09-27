package eu.kanade.tachiyomi.modelpack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 模型包描述符，对应模型包 APK 内的 `assets/modelpack.json`。
 *
 * 宿主不内置任何模型目录：模型叫什么、支持哪些倍率/降噪/精度、需要哪个推理引擎、
 * 模型文件放在包内哪个目录，全部由本描述符声明。新增模型包不需要更新宿主 APK。
 *
 * 模型包安装时 `assets/` 会被解压到 `filesDir/model-packs/<id>/`，
 * 因此这里直接读磁盘上的描述符，不再重新打开模型包 APK。
 */
@Serializable
data class ModelPackDescriptor(
    /** 描述符版本，宿主只理解 [ModelPackDescriptorParser.SUPPORTED_SCHEMA_VERSION]。 */
    val schemaVersion: Int = 1,
    /** 模型包标识，必须与 manifest 的 `tachiyomix.modelpack.id` 一致。 */
    val id: String = "",
    /** 模型包显示名。 */
    val name: String = "",
    /** 该包提供的模型列表。 */
    val models: List<ModelPackModel> = emptyList(),
    /** 包级可调项；缺失时宿主不展示对应的设置行。 */
    val options: ModelPackOptions? = null,
)

/**
 * 单个模型的描述。
 *
 * [engine] 决定用哪个推理后端，宿主内部维护 engine 字符串到原生初始化函数的映射
 * （见 `eu.kanade.tachiyomi.util.waifu2x.EnhancementEngines`）。
 * 未知 engine 只会导致该模型被跳过，不会崩溃。
 *
 * [assetPath] 是模型文件目录相对模型包解压根目录的路径，
 * 由宿主的 [ModelPackManager.findModelDirectory] 解析成绝对路径。
 */
@Serializable
data class ModelPackModel(
    /** 稳定唯一标识，也是持久化的用户选择值。 */
    val key: String,
    /** UI 展示名（纯字符串，模型包为外置 APK，无法引用宿主 i18n）。 */
    val name: String = "",
    val engine: String,
    val assetPath: String,
    /** 允许的放大倍率。 */
    val scales: List<Int> = emptyList(),
    /** 允许的降噪档位；空数组表示该模型没有降噪选项。 */
    val denoiseLevels: List<Int> = emptyList(),
    /** 允许的精度模式：0=FP16、1=FP32、2=INT8、3=BF16。 */
    val precisions: List<Int> = listOf(0, 1, 2, 3),
    /** 该模型是否有基于 Qualcomm NPU 的实现。 */
    val supportsNpu: Boolean = false,
    /** NPU 通路允许的倍率；缺省时等同于 [scales]。 */
    val npuScales: List<Int> = emptyList(),
    /** 各选项的回退默认值。 */
    val defaults: ModelPackDefaults = ModelPackDefaults(),
    /** 引擎支持的可选“风格”，例如 Real-ESRGAN 的 anime / photo。 */
    val styles: List<ModelPackStyle> = emptyList(),
    /** 默认风格值；缺省时取 [styles] 第一个。 */
    val defaultStyle: Int? = null,
    /** 提供该模型的模型包 id，由宿主注入（不在 JSON 中声明）。 */
    val packId: String = "",
)

/**
 * 引擎风格选项。每套风格可以覆盖模型文件目录、倍率与底层模型倍率。
 */
@Serializable
data class ModelPackStyle(
    val value: Int,
    val name: String,
    /** 覆盖模型级 [ModelPackModel.assetPath]；为空时沿用模型级取值。 */
    val assetPath: String? = null,
    /** 覆盖模型级 [ModelPackModel.scales]；为空时沿用模型级取值。 */
    val scales: List<Int> = emptyList(),
    /** 底层模型自身的倍率（例如 photo 风格用 4x 模型输出 2x）；为空时等同输出倍率。 */
    val modelScale: Int? = null,
)

/** 模型各选项的默认值，用于用户已存取值不在允许集合内时的回退。 */
@Serializable
data class ModelPackDefaults(
    val scale: Int? = null,
    val denoise: Int? = null,
    val style: Int? = null,
    val precision: Int? = null,
)

/** 分辨率设置项（0 表示不限制）。 */
@Serializable
data class ModelPackResolution(
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * 包级可调项。宿主持久化的仍是自己的偏好项，
 * 这里只决定「设置行是否可见」以及「可选值有哪些」。
 */
@Serializable
data class ModelPackOptions(
    val preloadPages: List<Int> = emptyList(),
    val gpuPerformanceModes: List<Int> = emptyList(),
    val tileSizes: List<Int> = emptyList(),
    val maxProcessingResolution: ModelPackResolution? = null,
    val maxResolution: ModelPackResolution? = null,
    val fp16Arithmetic: Boolean = false,
)

/**
 * 读取解压目录里的 `modelpack.json`。
 *
 * 描述符缺失或格式非法时返回 null 并打日志，绝不抛异常：宿主只跳过该包的模型。
 */
internal object ModelPackDescriptorParser {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val FILE_NAME = "modelpack.json"

    private const val TAG = "ModelPackDescriptor"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(packId: String, directory: File): ModelPackDescriptor? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) {
            android.util.Log.w(TAG, "Model pack $packId has no $FILE_NAME; ignoring its models")
            return null
        }

        val descriptor = try {
            json.decodeFromString(ModelPackDescriptor.serializer(), file.readText())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to parse $FILE_NAME of model pack $packId", e)
            return null
        }

        if (descriptor.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            android.util.Log.w(
                TAG,
                "Model pack $packId uses unsupported schemaVersion ${descriptor.schemaVersion}; ignoring its models",
            )
            return null
        }
        if (descriptor.id.isNotBlank() && descriptor.id != packId) {
            android.util.Log.w(
                TAG,
                "Model pack $packId declares id=${descriptor.id}; manifest id wins",
            )
        }
        if (descriptor.models.isEmpty()) {
            android.util.Log.w(TAG, "Model pack $packId declares no models")
            return null
        }

        return descriptor.copy(
            id = packId,
            models = descriptor.models
                .filter { it.key.isNotBlank() && it.engine.isNotBlank() && it.assetPath.isNotBlank() }
                .map { it.copy(packId = packId) },
        )
    }
}
