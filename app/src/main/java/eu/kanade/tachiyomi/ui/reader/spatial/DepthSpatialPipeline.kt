package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DepthSpatialPipeline(
    private val context: Context,
    private val cache: SpatialSceneCache = SpatialSceneCache(context),
    private val model: DepthSpatialModel = DepthSpatialModel(context),
) {
    sealed interface Result {
        data class Ready(val file: File, val fromCache: Boolean) : Result
        data object ModelMissing : Result

        // detail 携带原生侧的具体原因，便于在真机上定位（例如 deviceCreate 的错误码）
        data class RuntimeUnavailable(val detail: String) : Result
        data class Failed(val cause: Throwable) : Result
    }

    /** 生成阶段，供进度弹窗显示当前在做什么。 */
    enum class Stage {
        /** 读取并预处理当前页 */
        PREPARING,

        /** 已开启图像增强但该页尚未完成，等待增强结果 */
        WAITING_ENHANCEMENT,

        /** 首次在该设备上现场编译深度模型 */
        COMPILING,

        /** 估算深度并构建场景 */
        INFERRING,
    }

    /** [percent] 为 null 表示无法给出确定百分比，进度条走不确定动画。 */
    data class Progress(val stage: Stage, val percent: Int? = null)

    suspend fun create(
        page: ReaderPage,
        onCompilationStarted: suspend (htpArchitecture: Int?) -> Unit = {},
        onProgress: suspend (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        cache.cachedScene(page)?.let { return@withContext Result.Ready(it, fromCache = true) }
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val htpArchitecture = model.htpArchitecture ?: 0
        // 优先 HTP(DSP)，不可用时回退 CPU 后端（零售机 DSP 常拒绝无签名进程域）
        val backend = DepthQnnBridge.resolveBackend(nativeLibraryDir, htpArchitecture)
            ?: return@withContext Result.RuntimeUnavailable(DepthQnnBridge.lastError()).also {
                writeDiagnostics("runtime-unavailable detail=${DepthQnnBridge.lastError()}")
            }
        val useHtp = backend == "htp"
        val backendTag = if (useHtp) "htp-v$htpArchitecture" else backend
        if (!model.isReady) return@withContext Result.ModelMissing

        runCatching {
            val prepared = resolveDepthInputStream(page, onProgress)
                ?.use(DepthImagePreprocessor::prepare)
                ?: error("The current page has no readable image stream")
            val contextFile = model.contextFile(backendTag).apply { parentFile?.mkdirs() }
            if (!contextFile.isFile) {
                notifyProgress(onProgress, Progress(Stage.COMPILING))
                withContext(Dispatchers.Main.immediate) {
                    onCompilationStarted(if (useHtp) model.htpArchitecture else null)
                }
            } else {
                notifyProgress(onProgress, Progress(Stage.INFERRING))
            }
            val depthInputs = buildList {
                add(prepared.input)
                prepared.detailTiles.forEach { add(it.input) }
            }
            val depthResults = DepthQnnBridge.inferDepthBatch(
                inputs = depthInputs,
                modelPath = model.dlcFile.absolutePath,
                contextPath = contextFile.absolutePath,
                nativeLibraryDir = nativeLibraryDir,
                htpArchitecture = htpArchitecture,
                useHtp = useHtp,
            ) ?: error(DepthQnnBridge.lastError().ifBlank { "Depth Anything V3 QNN inference failed" })
            val scene = SpatialDepthSceneBuilder.build(
                prepared = prepared,
                depth = depthResults.first(),
                detailDepths = depthResults.drop(1),
            )
            cache.prepare(page)
            val outputFile = cache.sceneFile(page)
            val temporaryOutput = File(outputFile.parentFile, "${outputFile.name}.partial")
            temporaryOutput.delete()
            SpatialDepthSceneIO.write(temporaryOutput, scene)
            outputFile.delete()
            check(temporaryOutput.renameTo(outputFile)) { "Unable to commit the spatial scene cache" }
            Result.Ready(outputFile, fromCache = false)
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Spatial scene failed for chapter=${page.chapter.chapter.id}, page=${page.index}, " +
                    "variant=${page.enhancementKeySuffix.ifBlank { "full" }}",
                error,
            )
            writeDiagnostics(
                "backend=$backend failed detail=${error.message.orEmpty()} " +
                    "lastError=${DepthQnnBridge.lastError()}",
            )
            Result.Failed(error)
        }
    }

    /**
     * 决定景深用哪份源图：
     *  - 图像增强开启 → 用增强后的高清图；若还没生成，则先确保入队并等待增强处理完成再用。
     *  - 图像增强未开启 → 直接用原图。
     * 等待设了超时；超时或被判定为“超大图跳过”（永远不会产出增强图）时退回原图，避免卡死。
     */
    private suspend fun resolveDepthInputStream(
        page: ReaderPage,
        onProgress: suspend (Progress) -> Unit,
    ): InputStream? {
        // 未开启图像增强：直接深度处理原图
        if (!ReaderEnhancement.isEnabled()) {
            notifyProgress(onProgress, Progress(Stage.PREPARING))
            return page.stream?.invoke()
        }

        // 增强已开启：已生成增强图则直接用
        ReaderEnhancement.cachedFile(context, page)?.let {
            notifyProgress(onProgress, Progress(Stage.PREPARING))
            return it.inputStream()
        }

        // 尚未生成：兜底确保该页已进入增强队列（当前可见页通常已高优先级入队，重复触发是幂等的）
        ReaderEnhancement.request(context, page, highPriority = true)

        val deadline = System.currentTimeMillis() + ENHANCE_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // 等待期间只有增强在处理，直接把它自己的百分比作为进度显示
            notifyProgress(onProgress, Progress(Stage.WAITING_ENHANCEMENT, Waifu2x.getProgressPercent()))
            delay(ENHANCE_POLL_INTERVAL_MS)
            ReaderEnhancement.cachedFile(context, page)?.let { return it.inputStream() }
            // 被判定为超大图跳过，不会再产出增强图，退回原图
            if (ReaderEnhancement.isSkipped(context, page)) break
        }
        notifyProgress(onProgress, Progress(Stage.PREPARING))
        return page.stream?.invoke()
    }

    private suspend fun notifyProgress(onProgress: suspend (Progress) -> Unit, progress: Progress) {
        withContext(Dispatchers.Main.immediate) { onProgress(progress) }
    }

    /**
     * 把诊断信息写到 App 的外部目录（`Android/data/<包名>/files/spatial-debug.log`）。
     * 部分机型（例如 nubia）在正式版 ROM 上完全关闭了 logcat，只能靠这个文件排查，可用 adb pull 取回。
     */
    private fun writeDiagnostics(message: String) {
        runCatching {
            val directory = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(directory, DIAGNOSTIC_FILE)
            val qnnLibraries = File(context.applicationInfo.nativeLibraryDir)
                .listFiles()
                ?.filter { it.name.startsWith("libQnn") || it.name.startsWith("libQairt") }
                ?.joinToString(",") { it.name }
                .orEmpty()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText(
                buildString {
                    appendLine("[$timestamp] $message")
                    appendLine(
                        "    sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} soc=${Build.SOC_MODEL} " +
                            "arch=${model.htpArchitecture} modelReady=${model.isReady}",
                    )
                    appendLine("    compiled=${compiledContexts()}")
                    appendLine("    qnnLibs=$qnnLibraries")
                },
            )
        }
    }

    /** 已编译的 context 文件列表（区分 HTP/CPU 后端），便于排查 */
    private fun compiledContexts(): String =
        File(model.directory, "compiled")
            .listFiles()
            ?.joinToString(",") { "${it.name}:${it.length()}" }
            .orEmpty()

    private companion object {
        const val TAG = "DepthSpatialPipeline"
        const val DIAGNOSTIC_FILE = "spatial-debug.log"

        // 专等增强处理完成的最大时长与轮询间隔
        const val ENHANCE_WAIT_TIMEOUT_MS = 120_000L
        const val ENHANCE_POLL_INTERVAL_MS = 500L
    }
}

internal object DepthQnnBridge {
    private const val INPUT_ELEMENTS = 518 * 518 * 3
    private const val OUTPUT_ELEMENTS = 518 * 518
    private val loaded = runCatching {
        System.loadLibrary("waifu2x-jni")
        true
    }.getOrDefault(false)

    /** 返回 "htp" / "cpu"；都不可用时返回 null，并以 lastError() 给出原因。 */
    fun resolveBackend(nativeLibraryDir: String, htpArchitecture: Int): String? = if (loaded) {
        runCatching { nativeResolveBackend(nativeLibraryDir, htpArchitecture) }
            .getOrDefault("")
            .takeIf { it.isNotEmpty() }
    } else {
        null
    }

    fun inferDepth(
        input: FloatArray,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
        htpArchitecture: Int,
        useHtp: Boolean,
    ): FloatArray? = if (loaded) {
        nativeInferDepth(input, modelPath, contextPath, nativeLibraryDir, htpArchitecture, useHtp)
    } else {
        null
    }

    fun inferDepthBatch(
        inputs: List<FloatArray>,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
        htpArchitecture: Int,
        useHtp: Boolean,
    ): List<FloatArray>? {
        if (!loaded || inputs.isEmpty() || inputs.size > 4 || inputs.any { it.size != INPUT_ELEMENTS }) {
            return null
        }
        val combinedInputs = FloatArray(inputs.size * INPUT_ELEMENTS)
        inputs.forEachIndexed { index, input ->
            input.copyInto(combinedInputs, destinationOffset = index * INPUT_ELEMENTS)
        }
        val combinedOutputs = nativeInferDepthBatch(
            inputs = combinedInputs,
            batchCount = inputs.size,
            modelPath = modelPath,
            contextPath = contextPath,
            nativeLibraryDir = nativeLibraryDir,
            htpArchitecture = htpArchitecture,
            useHtp = useHtp,
        ) ?: return null
        if (combinedOutputs.size != inputs.size * OUTPUT_ELEMENTS) return null
        return List(inputs.size) { index ->
            combinedOutputs.copyOfRange(index * OUTPUT_ELEMENTS, (index + 1) * OUTPUT_ELEMENTS)
        }
    }

    fun lastError(): String = if (loaded) runCatching { nativeLastError() }.getOrDefault("") else ""

    private external fun nativeResolveBackend(
        nativeLibraryDir: String,
        htpArchitecture: Int,
    ): String
    private external fun nativeInferDepth(
        input: FloatArray,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
        htpArchitecture: Int,
        useHtp: Boolean,
    ): FloatArray?
    private external fun nativeInferDepthBatch(
        inputs: FloatArray,
        batchCount: Int,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
        htpArchitecture: Int,
        useHtp: Boolean,
    ): FloatArray?
    private external fun nativeLastError(): String
}
