package eu.kanade.tachiyomi.modelpack

import java.io.File

/**
 * 外置安装的 AI 模型包（waifu2x / Real-CUGAN / Real-ESRGAN 等）。
 *
 * 模型包是无代码的 APK，宿主无法直接读取其它 APK 的 AssetManager，
 * 因此安装后会把包内 `assets/` 解压到 [directory]，模型按原有相对路径存放。
 */
data class ModelPack(
    /** 模型包标识，与内置 assets 的目录名一致，例如 `waifu2x-models` 所属包为 `waifu2x` */
    val id: String,
    val name: String,
    val packageName: String,
    val versionCode: Long,
    /** 解压后的根目录：`filesDir/model-packs/<id>` */
    val directory: File,
    val sizeBytes: Long,
    /** 模型包 APK 路径，用于按需解出包内的原生推理库。 */
    val apkPath: String = "",
    /** 系统为该包解出的原生库目录，通常是 `.../lib/<abi>`。 */
    val nativeLibraryDir: String? = null,
)

/** 目录内是否存在 ncnn 模型文件（`.bin` + `.param`）。 */
internal fun File.containsModelFiles(): Boolean =
    walkTopDown().any { it.isFile && (it.extension == "bin" || it.extension == "param") }

/**
 * 目录是否是有效的模型包解压结果。
 *
 * 常规模型包带 `.bin`/`.param`；纯原生实现（只提供 `libmodelpack.so` + 描述符）没有模型文件，
 * 因此描述符本身也算作有效内容。
 */
internal fun File.isValidModelPack(): Boolean =
    containsModelFiles() || File(this, ModelPackDescriptorParser.FILE_NAME).isFile

internal fun File.directorySize(): Long =
    walkTopDown().filter { it.isFile }.sumOf { it.length() }
