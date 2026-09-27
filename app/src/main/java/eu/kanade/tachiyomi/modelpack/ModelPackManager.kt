package eu.kanade.tachiyomi.modelpack

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 外置模型包的运行时注册表。
 *
 * 模型包只能通过系统安装器装到设备上，宿主不参与安装流程；
 * 这里负责扫描已安装的模型包、按需解压、解析包内 `modelpack.json`，
 * 并把结果缓存起来供推理链路与设置页查询。
 *
 * 宿主自身不保存任何模型目录：模型清单完全来自已安装模型包。
 */
object ModelPackManager {
    private val mutablePacks = MutableStateFlow<List<ModelPack>>(emptyList())
    val packs: StateFlow<List<ModelPack>> = mutablePacks.asStateFlow()

    private val mutableModels = MutableStateFlow<List<ModelPackModel>>(emptyList())

    /** 已安装模型包提供的全部模型；未安装的模型包不会出现在这里。 */
    val models: StateFlow<List<ModelPackModel>> = mutableModels.asStateFlow()

    private val mutablePackOptions = MutableStateFlow<Map<String, ModelPackOptions>>(emptyMap())

    /** 模型包 id → 包级可调项。 */
    val packOptions: StateFlow<Map<String, ModelPackOptions>> = mutablePackOptions.asStateFlow()

    @Volatile
    private var scanned = false

    /** 已安装的模型包（首次调用会执行扫描与解压）。 */
    fun installed(context: Context): List<ModelPack> {
        if (!scanned) refresh(context)
        return mutablePacks.value
    }

    /** 重新扫描已安装的模型包。模型包增删后调用。 */
    fun refresh(context: Context) {
        val applicationContext = context.applicationContext
        val loaded = runCatching {
            ModelPackLoader.loadPacks(applicationContext)
        }.getOrDefault(emptyList())

        // 清理已卸载模型包残留的解压文件
        val installedIds = loaded.mapTo(mutableSetOf()) { it.id }
        File(applicationContext.filesDir, MODEL_PACK_DIR)
            .listFiles()
            ?.forEach { dir ->
                if (dir.isDirectory && dir.name !in installedIds) {
                    dir.deleteRecursively()
                }
            }

        // 逐个读取描述符：单个包描述符损坏只影响它自己的模型
        val descriptors = loaded.mapNotNull { pack ->
            ModelPackDescriptorParser.parse(pack.id, pack.directory)?.let { pack.id to it }
        }

        mutablePacks.value = loaded.sortedBy { it.id }
        // 模型按首字母（中文按拼音）排序，方便阅读器设置里挑选
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        mutableModels.value = descriptors
            .flatMap { (_, descriptor) -> descriptor.models }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
        mutablePackOptions.value = descriptors
            .mapNotNull { (id, descriptor) -> descriptor.options?.let { id to it } }
            .toMap()
        scanned = true

        // 诊断：记录识别到的模型包，并直接尝试加载其原生库（无需进入阅读器触发增强）
        appendEnhanceDebugLog(applicationContext, "scan: packs=${loaded.map { it.id }}")
        loaded.forEach { pack ->
            appendEnhanceDebugLog(
                applicationContext,
                "scan: pack=${pack.id} nativeLibraryDir=${pack.nativeLibraryDir} " +
                    "apk=${pack.apkPath} dir=${pack.directory}",
            )
            val lib = pack.nativeLibraryDir?.let { File(it, "libmodelpack.so") }
            val handle = if (lib != null && lib.isFile) {
                runCatching {
                    eu.kanade.tachiyomi.util.waifu2x.PackNativeBridge.load(lib.absolutePath)
                }.getOrDefault(0L)
            } else {
                0L
            }
            appendEnhanceDebugLog(
                applicationContext,
                "scan: pack=${pack.id} probeLib=${lib?.absolutePath} exists=${lib?.isFile} dlopen=$handle",
            )
            if (handle != 0L) {
                runCatching { eu.kanade.tachiyomi.util.waifu2x.PackNativeBridge.release(handle) }
            }
        }
    }

    /** 所有已安装模型包提供的模型（安装顺序：模型包 id 升序）。 */
    fun installedModels(context: Context): List<ModelPackModel> {
        if (!scanned) refresh(context)
        return mutableModels.value
    }

    /** 按描述符里的模型 key 查找模型；未安装对应模型包时返回 null。 */
    fun findModel(context: Context, key: String): ModelPackModel? =
        installedModels(context).firstOrNull { it.key == key }

    /** 取某个模型包声明的包级可调项。 */
    fun optionsFor(context: Context, packId: String): ModelPackOptions? {
        if (!scanned) refresh(context)
        return mutablePackOptions.value[packId]
    }

    /**
     * 按内置 assets 的相对路径查找模型目录，例如 `waifu2x-models`、
     * `realesrgan-models/v3-anime`。未安装对应模型包时返回 null。
     */
    fun findModelDirectory(context: Context, assetPath: String): String? =
        installed(context).firstNotNullOfOrNull { pack ->
            File(pack.directory, assetPath)
                .takeIf { it.isDirectory && it.containsModelFiles() }
                ?.absolutePath
        }

    /**
     * 按描述符解析某个模型（可覆盖 [assetPath]，用于风格切换）的模型文件目录。
     * 未安装对应模型包或目录不存在时返回 null。
     */
    fun findModelDirectory(
        context: Context,
        model: ModelPackModel,
        assetPath: String = model.assetPath,
    ): String? {
        val pack = installed(context).firstOrNull { it.id == model.packId } ?: return null
        return File(pack.directory, assetPath)
            .takeIf { it.isDirectory && it.containsModelFiles() }
            ?.absolutePath
    }

    /** 清除模型包解压出来的文件（卸载模型包后调用）。 */
    fun deleteExtractedData(context: Context, id: String) {
        File(File(context.filesDir, MODEL_PACK_DIR), id).deleteRecursively()
        mutablePacks.value = mutablePacks.value.filterNot { it.id == id }
        mutableModels.value = mutableModels.value.filterNot { it.packId == id }
        mutablePackOptions.value = mutablePackOptions.value.filterKeys { it != id }
    }

    internal fun packOf(context: Context, packId: String): ModelPack? =
        installed(context).firstOrNull { it.id == packId }

    private const val MODEL_PACK_DIR = "model-packs"
}

/** 诊断日志文件名，位于应用外部私有目录，便于 `adb pull` 获取。 */
internal const val ENHANCE_DEBUG_LOG = "enhance_debug.log"

/**
 * 图像增强诊断日志：把关键诊断信息落盘保存。
 *
 * 部分 ROM（如 ColorOS）的 logcat 缓冲极小，应用日志几秒内就被系统日志挤出，
 * 排查时无法依赖 logcat，因此这里直接写入应用外部私有目录
 * （`/sdcard/Android/data/<包名>/files/enhance_debug.log`），可由 adb 拉取。
 */
internal fun appendEnhanceDebugLog(context: Context, message: String) {
    runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        File(dir, ENHANCE_DEBUG_LOG).appendText("$stamp $message\n")
    }
}
