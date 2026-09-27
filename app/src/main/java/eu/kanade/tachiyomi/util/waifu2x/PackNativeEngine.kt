package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import eu.kanade.tachiyomi.modelpack.ModelPack
import eu.kanade.tachiyomi.modelpack.ModelPackManager
import eu.kanade.tachiyomi.modelpack.ModelPackModel
import eu.kanade.tachiyomi.modelpack.appendEnhanceDebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.core.content.pm.PackageInfoCompat
import android.app.Application
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private const val TAG = "PackNativeEngine"

/**
 * 模型包自带推理库的可选通路。
 *
 * 模型包可以在 `lib/<abi>/libmodelpack.so` 里带自己的推理实现（见 `app/src/main/cpp/modelpack_abi.h`
 * 的稳定 C ABI）。宿主加载成功时，该包的模型优先走包内实现；加载失败或包内没有 `.so`
 * 时回退到内置引擎，行为与之前完全一致。
 *
 * 查找顺序：
 * 1. 系统为模型包解出的原生库目录（`ApplicationInfo.nativeLibraryDir`）；
 * 2. 包内 `lib/<设备 ABI>/libmodelpack.so`，供系统未解出原生库（`extractNativeLibs=false`）时使用。
 */
object PackNativeEngines {
    private const val LIB_FILE_NAME = "libmodelpack.so"

    /** 宿主实现的 ABI 版本，必须与包内实现一致。 */
    const val MODELPACK_ABI_VERSION = 1

    private val handles = ConcurrentHashMap<String, Long>()
    private val json = Json { encodeDefaults = true }

    fun engineFor(context: Context, model: ModelPackModel): EnhancementEngine? {
        val pack = ModelPackManager.packOf(context, model.packId)
        if (pack == null) {
            val installed = ModelPackManager.installed(context).map { it.id }
            appendEnhanceDebugLog(
                context,
                "engineFor: no pack for model=${model.key} packId='${model.packId}' installed=$installed",
            )
            return null
        }
        val libInNativeDir = pack.nativeLibraryDir?.let { File(it, LIB_FILE_NAME) }
        val extractedLib = File(pack.directory, "lib/$LIB_FILE_NAME")
        val handle = handleFor(pack)
        appendEnhanceDebugLog(
            context,
            "engineFor: model=${model.key} pack=${pack.id} handle=$handle " +
                "nativeLibraryDir=${pack.nativeLibraryDir} nativeLibExists=${libInNativeDir?.isFile} " +
                "extracted=${extractedLib.absolutePath} extractedExists=${extractedLib.isFile} " +
                "apkPath=${pack.apkPath}",
        )
        if (handle == null) {
            appendEnhanceDebugLog(context, "engineFor: pack ${pack.id} native engine unavailable, falling back")
            return null
        }
        return PackNativeEngine(handle, model.engine)
    }

    private fun handleFor(pack: ModelPack): Long? {
        // 只复用成功加载的句柄；加载失败不缓存，模型包更新后可在同一进程内重试
        // dlopen 返回的是指针，在 arm64 上高位置位后按有符号 long 解释为负数；
        // 因此这里以 `!= 0` 判定成功（0 表示失败），不能用 `> 0`。
        handles[pack.id]?.takeIf { it != 0L }?.let { return it }
        val handle = synchronized(handles) {
            handles[pack.id]?.takeIf { it != 0L } ?: run {
                val loaded = runCatching { loadLibrary(pack) }.getOrDefault(0L)
                if (loaded != 0L) handles[pack.id] = loaded
                loaded
            }
        }
        return handle.takeIf { it != 0L }
    }

    private fun loadLibrary(pack: ModelPack): Long {
        val library = findLibraryFile(pack)
        if (library == null) {
            // 绝大多数模型包不带原生库；用 ERROR 级便于在各类 ROM 上排查
            android.util.Log.e(TAG, "Model pack ${pack.id} ships no $LIB_FILE_NAME; using built-in engines")
            return 0L
        }
        val handle = PackNativeBridge.load(library.absolutePath)
        if (handle == 0L) {
            android.util.Log.e(TAG, "Model pack ${pack.id}: failed to load ${library.absolutePath}")
            return 0L
        }
        val abiVersion = PackNativeBridge.abiVersion(handle)
        if (abiVersion != MODELPACK_ABI_VERSION) {
            android.util.Log.e(
                TAG,
                "Model pack ${pack.id}: ABI version $abiVersion is not supported ($MODELPACK_ABI_VERSION)",
            )
            PackNativeBridge.release(handle)
            return 0L
        }
        android.util.Log.e(
            TAG,
            "Model pack ${pack.id}: loaded native engine ${PackNativeBridge.describe(handle)?.take(80) ?: ""}",
        )
        return handle
    }

    private fun findLibraryFile(pack: ModelPack): File? {
        pack.nativeLibraryDir?.let { directory ->
            File(directory, LIB_FILE_NAME).takeIf { it.isFile }?.let {
                android.util.Log.e(TAG, "pack ${pack.id}: lib found in nativeLibraryDir: ${it.absolutePath}")
                return it
            }
        }
        android.util.Log.e(
            TAG,
            "pack ${pack.id}: no lib in nativeLibraryDir=${pack.nativeLibraryDir}; trying to extract from APK",
        )
        if (pack.apkPath.isBlank()) return null

        val target = File(pack.directory, "lib/$LIB_FILE_NAME")
        if (target.isFile) return target

        val abi = Build.SUPPORTED_ABIS.firstOrNull { it.isNotBlank() } ?: return null
        return try {
            ZipFile(pack.apkPath).use { zip ->
                val entry = zip.getEntry("lib/$abi/$LIB_FILE_NAME") ?: return null
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use(input::copyTo)
                }
                target
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Model pack ${pack.id}: failed to extract $LIB_FILE_NAME", e)
            null
        }
    }
}

/** 传给 `modelpack_init` 的选项 JSON。 */
@Serializable
private data class PackEngineOptions(
    val model: String,
    val noise: Int,
    val scale: Int,
    val precision: Int,
    val fp16Arithmetic: Boolean,
    val tileSize: Int,
    val tileSleepMs: Int,
    val backend: Int,
    val style: Int,
)

/** 把模型包自带库包装成引擎。 */
private class PackNativeEngine(
    private val handle: Long,
    override val id: String,
) : EnhancementEngine {
    @Volatile
    private var outputScale = 1

    override fun init(context: Context, model: ModelPackModel, settings: EnhancementSettings): Boolean {
        outputScale = settings.scale.coerceAtLeast(1)
        val options = PackEngineOptions(
            model = model.key,
            noise = settings.noise,
            scale = settings.scale,
            precision = settings.precision,
            fp16Arithmetic = settings.fp16Arithmetic,
            tileSize = settings.tileSize,
            tileSleepMs = settings.tileSleepMs,
            backend = settings.backend,
            style = settings.style,
        )
        val ok = PackNativeBridge.init(handle, model.key, Json.encodeToString(options))
        appendEnhanceDebugLog(
            Injekt.get<Application>(),
            "engine: init model=${model.key} pack=$id scale=${settings.scale} ok=$ok",
        )
        return ok
    }

    override fun process(input: Bitmap, pageIndex: Int): Bitmap? {
        val source = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }
        val ownsSource = source !== input
        var output: Bitmap? = null
        try {
            val width = (source.width * outputScale).coerceAtLeast(1)
            val height = (source.height * outputScale).coerceAtLeast(1)
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val ok = PackNativeBridge.process(handle, source, output)
            appendEnhanceDebugLog(
                Injekt.get<Application>(),
                "engine: process pack=$id page=$pageIndex w=${source.width}x${source.height}->${width}x${height} ok=$ok",
            )
            if (ok) return output
            output.recycle()
            return null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Model pack engine $id failed to process the page", e)
            output?.takeIf { !it.isRecycled }?.recycle()
            return null
        } finally {
            if (ownsSource && !source.isRecycled) source.recycle()
        }
    }
}

/** 指向宿主 `waifu2x-jni` 里的模型包 ABI 桥接函数。 */
internal object PackNativeBridge {
    private val available: Boolean = try {
        System.loadLibrary("waifu2x-jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun load(path: String): Long = call { nativeLoad(path) } ?: 0L

    fun abiVersion(handle: Long): Int = call { nativeAbiVersion(handle) } ?: -1

    fun describe(handle: Long): String? = call { nativeDescribe(handle) }

    fun init(handle: Long, modelKey: String, optionsJson: String): Boolean =
        call { nativeInit(handle, modelKey, optionsJson) } ?: false

    fun process(handle: Long, input: Bitmap, output: Bitmap): Boolean =
        call { nativeProcess(handle, input, output) } ?: false

    fun release(handle: Long) {
        call { nativeRelease(handle) }
    }

    private inline fun <T> call(block: () -> T): T? =
        if (available) runCatching(block).getOrNull() else null

    private external fun nativeLoad(path: String): Long
    private external fun nativeAbiVersion(handle: Long): Int
    private external fun nativeDescribe(handle: Long): String?
    private external fun nativeInit(handle: Long, modelKey: String, optionsJson: String): Boolean
    private external fun nativeProcess(handle: Long, input: Bitmap, output: Bitmap): Boolean
    private external fun nativeRelease(handle: Long)
}
