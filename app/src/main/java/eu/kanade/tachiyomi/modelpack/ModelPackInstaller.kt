package eu.kanade.tachiyomi.modelpack

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProxyScope
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.INSTALLERX_PACKAGE_NAME
import eu.kanade.tachiyomi.util.system.installerXPackageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 模型包安装失败的原因，按**阶段**区分：用户要能一眼看出是「下载失败」还是「安装失败」。
 *
 * @param stage 失败阶段（下载 / 校验 / 装包）
 * @param message 可直接展示给用户的原因（HTTP 码、超时、sha256 不符、版本不符等）
 */
internal class ModelPackInstallException(
    val stage: Stage,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Stage {
        DOWNLOAD,
        VERIFY,
        INSTALL,
    }
}

/**
 * 从插件目录里下载模型包 APK 并交给系统安装器安装/更新。
 *
 * - 走 [ProxyScope.MODEL_PACK_DOWNLOAD] 作用域：没勾「走代理」时一律直连；
 * - **下载源有镜像兜底**：GitHub Release 在国内经常连得上却拉不动（实测 TLS 直接超时），
 *   所以原始地址失败后依次尝试 [MIRROR_PREFIXES]（`ghproxy` 这类反代），先到先用；
 * - **下载完先体检**：sha256（目录给了就核对）、包名、versionCode —— 三者任一不对就明确报错，
 *   而不是把一个装不上的 APK 丢给系统安装器让它静默失败；
 * - **三个阶段都写诊断日志**到 `files/enhance_debug.log`（ColorOS 这类 ROM 的 logcat 缓冲极小，
 *   只写 logcat 会查不到现场），日志前缀统一为 `modelpack:`；
 * - 安装这一步复用扩展的那套 [ExtensionInstallActivity]（同一份「安装程序」偏好、同样的 MIUI
 *   兼容处理与临时文件清理），模型包没有「私有安装」通路，因此固定走系统安装器那一支。
 */
internal class ModelPackInstaller(private val context: Context) {
    private val network = Injekt.get<NetworkHelper>()
    private val installerPreference = Injekt.get<BasePreferences>().extensionInstaller

    suspend fun downloadAndInstall(pack: CatalogModelPack): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val apk = downloadApk(pack)
            verify(pack, apk)
            withContext(Dispatchers.Main) { launchInstaller(pack, apk) }
            log(pack, "OK 已交给安装器，用时 ${System.currentTimeMillis() - startedAt}ms")
            Result.success(Unit)
        } catch (e: Exception) {
            val stage = (e as? ModelPackInstallException)?.stage?.name ?: "UNKNOWN"
            log(pack, "FAILED stage=$stage error=${e.message}", e)
            Result.failure(e)
        }
    }

    private fun downloadApk(pack: CatalogModelPack): File {
        val url = pack.apk?.takeIf { it.isNotBlank() }
            ?: throw ModelPackInstallException(
                ModelPackInstallException.Stage.DOWNLOAD,
                "目录里没有 ${pack.id} 的 APK 地址",
            )

        val target = File(context.cacheDir, "modelpack-${pack.id}.apk")
        // 顺序：镜像在前、原始 GitHub 地址在后。原因见 [MIRROR_PREFIXES] —— 国内直连
        // github.com 经常"连得上但一个字节都拉不动"，放前面就是白等几分钟。
        val candidates = buildList {
            if (url.startsWith("https://github.com/")) {
                MIRROR_PREFIXES.forEach { add(it to true) }
            }
            add(url to false)
        }

        var lastError: String? = null
        candidates.forEachIndexed { index, (candidate, isMirror) ->
            log(pack, "download try ${index + 1}/${candidates.size} mirror=$isMirror $candidate")
            try {
                fetch(candidate, target, isMirror)

                // 反代镜像经常"HTTP 200 + 一个 HTML 错误页"，必须验内容而不是只看状态码，
                // 而且**坏源要跳到下一个**，不能让一个坏镜像把整次安装判死。
                if (!target.looksLikeApk()) {
                    lastError = "返回的不是 APK（${target.length()} B）"
                    log(pack, "download try ${index + 1} 内容不对：$lastError")
                    target.delete()
                    return@forEachIndexed
                }
                pack.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                    val actual = target.sha256()
                    if (!actual.equals(expected, ignoreCase = true)) {
                        lastError = "sha256 不符（${actual.take(12)}…）"
                        log(pack, "download try ${index + 1} sha256 不对：$lastError")
                        target.delete()
                        return@forEachIndexed
                    }
                }

                log(pack, "download done ${target.length()} B via ${candidate.take(40)}…")
                return target
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
                log(pack, "download try ${index + 1} failed: $lastError")
                target.delete()
            }
        }

        throw ModelPackInstallException(
            ModelPackInstallException.Stage.DOWNLOAD,
            "${candidates.size} 个下载源都失败（$lastError）",
        )
    }

    private fun fetch(url: String, target: File, isMirror: Boolean) {
        val client = network.clientFor(ProxyScope.MODEL_PACK_DOWNLOAD)
            .newBuilder()
            // 镜像源正常时是持续有数据的，超时给宽一点；GitHub 直连只给很短预算，好尽快跳到下一个源
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(if (isMirror) 60 else 15, TimeUnit.SECONDS)
            .callTimeout(if (isMirror) 5 else 1, TimeUnit.MINUTES)
            .build()

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw ModelPackInstallException(
                    ModelPackInstallException.Stage.DOWNLOAD,
                    "HTTP ${response.code}",
                )
            }
            response.body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun verify(pack: CatalogModelPack, apk: File) {
        // sha256 已经在下载循环里逐个源核对过了，这里只做「是不是这个插件、是不是真的更新了」的判断
        val info = packageInfo(apk)
            ?: throw ModelPackInstallException(
                ModelPackInstallException.Stage.VERIFY,
                "无法解析 APK（文件不完整或不是安装包）",
            )

        if (pack.pkg.isNotBlank() && info.packageName != pack.pkg) {
            throw ModelPackInstallException(
                ModelPackInstallException.Stage.VERIFY,
                "包名不符：${info.packageName} ≠ ${pack.pkg}",
            )
        }

        val installed = ModelPackManager.installed(context).firstOrNull { it.id == pack.id }
        if (installed != null && info.longVersionCode <= installed.versionCode) {
            throw ModelPackInstallException(
                ModelPackInstallException.Stage.VERIFY,
                "下载到的 APK versionCode=${info.longVersionCode}，不比已装的 ${installed.versionCode} 新" +
                    "（说明目录里的版本信息比实际发布的包新，需要重新生成目录索引）",
            )
        }
        if (pack.code > 0 && info.longVersionCode != pack.code) {
            throw ModelPackInstallException(
                ModelPackInstallException.Stage.VERIFY,
                "versionCode 不符：APK=${info.longVersionCode}，目录=${pack.code}",
            )
        }

        log(pack, "verify ok pkg=${info.packageName} code=${info.longVersionCode}")
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(apk: File): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
    }

    private fun launchInstaller(pack: CatalogModelPack, apk: File) {
        val installer = installerPreference.get()
        try {
            val intent = Intent(context, ExtensionInstallActivity::class.java)
                .setDataAndType(apk.getUriCompat(context), ExtensionInstaller.APK_MIME)
                .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, apk.name.hashCode().toLong())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // 与扩展一致：用户在「安装程序」里选了 InstallerX 时直接指定目标，不再让系统弹选择框
            if (installer == BasePreferences.ExtensionInstaller.INSTALLERX) {
                intent.putExtra(
                    ExtensionInstaller.EXTRA_TARGET_PACKAGE,
                    context.installerXPackageName ?: INSTALLERX_PACKAGE_NAME,
                )
            }

            context.startActivity(intent)
            log(pack, "installer launched preference=$installer")
        } catch (e: Exception) {
            throw ModelPackInstallException(
                ModelPackInstallException.Stage.INSTALL,
                e.message ?: e::class.java.simpleName,
                e,
            )
        }
    }

    /** 落盘诊断日志（`adb shell tail .../files/enhance_debug.log` 就能看到整个流程）。 */
    private fun log(pack: CatalogModelPack, message: String, e: Throwable? = null) {
        appendEnhanceDebugLog(context, "modelpack: ${pack.id} $message")
        if (e != null) {
            logcat(LogPriority.ERROR, e) { "模型包 ${pack.id} $message" }
        }
    }

    /** APK 本质是 ZIP：前 4 字节必须是 `PK\x03\x04`，用来一眼识破镜像返回的 HTML 错误页。 */
    private fun File.looksLikeApk(): Boolean = length() > 4 && inputStream().use { stream ->
        val magic = ByteArray(4)
        stream.read(magic) == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * GitHub Release 的反代前缀（直接拼在原地址前面），按实测可用性排序。
         *
         * 国内直连 `github.com/…/releases/download/…` 经常是"能 ping 通、拉不动"（TLS 超时），
         * 而反代镜像良莠不齐：实测 `ghfast.top` 能返回真正的 APK，`ghproxy.net` / `gh-proxy.com`
         * 可能返回 403 或一个 HTML 错误页 —— 所以下载循环里会验内容并跳到下一个源。
         */
        private val MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://mirror.ghproxy.com/",
            "https://gh-proxy.com/",
        )
    }
}
