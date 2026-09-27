package eu.kanade.tachiyomi.modelpack

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.util.zip.ZipFile

/**
 * 发现并解压外置「模型包」APK。
 *
 * 模型包契约：
 * 1. 声明 `<uses-feature android:name="tachiyomix.modelpack" android:required="false" />`
 * 2. 通过 meta-data 声明 `tachiyomix.modelpack.id` / `tachiyomix.modelpack.name`
 * 3. 模型文件放在 `assets/` 下，相对路径与原先内置 assets 保持一致
 * 4. `assets/modelpack.json` 声明该包提供哪些模型、引擎与可选值
 *
 * 宿主只拿得到模型包的 APK 路径，所以把它当 zip 打开，
 * 将 `assets/` 下的内容解压到 `filesDir/model-packs/<id>/`，保持原有相对路径。
 */
internal object ModelPackLoader {
    const val FEATURE = "tachiyomix.modelpack"

    private const val METADATA_ID = "tachiyomix.modelpack.id"
    private const val METADATA_NAME = "tachiyomix.modelpack.name"
    private const val ASSET_PREFIX = "assets/"
    private const val STATE_FILE = ".pack-state"

    // GET_CONFIGURATIONS 是必需的：只有带上它 PackageInfo.reqFeatures 才会被填充，
    // 否则模型包的 uses-feature 读不到（扩展加载器同样依赖该 flag 判断 tachiyomi.extension）。
    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA

    fun loadPacks(context: Context): List<ModelPack> =
        getInstalledPackages(context.packageManager)
            .filter { it.isModelPack() }
            .mapNotNull { it.toModelPack(context) }

    @Suppress("DEPRECATION")
    private fun getInstalledPackages(packageManager: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()),
            )
        } else {
            packageManager.getInstalledPackages(PACKAGE_FLAGS)
        }

    private fun PackageInfo.isModelPack(): Boolean =
        reqFeatures?.any { it.name == FEATURE } == true

    private fun PackageInfo.toModelPack(context: Context): ModelPack? {
        val appInfo = applicationInfo ?: return null
        val id = appInfo.metaData?.getString(METADATA_ID)?.takeIf { it.isNotBlank() } ?: return null
        val name = appInfo.metaData?.getString(METADATA_NAME)?.takeIf { it.isNotBlank() } ?: id
        val apkPath = appInfo.sourceDir ?: return null
        val versionCode = PackageInfoCompat.getLongVersionCode(this)

        val directory = extract(context, id, apkPath, packageName, versionCode) ?: return null
        return ModelPack(
            id = id,
            name = name,
            packageName = packageName,
            versionCode = versionCode,
            directory = directory,
            sizeBytes = directory.directorySize(),
            apkPath = apkPath,
            nativeLibraryDir = appInfo.nativeLibraryDir,
        )
    }

    /**
     * 把模型包的 assets 目录解压到 `filesDir/model-packs/<id>/`。
     * 已按相同的包名+版本解压过则直接复用，避免每次启动重复拷贝。
     */
    private fun extract(
        context: Context,
        id: String,
        apkPath: String,
        packageName: String,
        versionCode: Long,
    ): File? {
        val target = File(context.filesDir, "model-packs/$id")
        val stateFile = File(target, STATE_FILE)
        val expectedState = "$packageName:$versionCode"
        if (stateFile.isFile &&
            stateFile.readText() == expectedState &&
            target.isValidModelPack()
        ) {
            return target
        }

        target.deleteRecursively()
        target.mkdirs()
        val targetRoot = target.canonicalPath + File.separator
        try {
            ZipFile(apkPath).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith(ASSET_PREFIX)) continue
                    val relative = entry.name.removePrefix(ASSET_PREFIX)
                    if (relative.isBlank()) continue
                    val output = File(target, relative)
                    // 防止压缩包内使用 ../ 路径穿越写出目标目录
                    if (!output.canonicalPath.startsWith(targetRoot)) continue
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use(input::copyTo)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ModelPackLoader", "Failed to extract model pack $id", e)
            target.deleteRecursively()
            return null
        }

        if (!target.isValidModelPack()) {
            target.deleteRecursively()
            return null
        }
        stateFile.writeText(expectedState)
        return target
    }
}
