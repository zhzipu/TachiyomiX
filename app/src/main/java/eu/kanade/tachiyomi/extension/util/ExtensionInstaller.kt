package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.installer.RootInstaller
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProxyScope
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.INSTALLERX_PACKAGE_NAME
import eu.kanade.tachiyomi.util.system.installerXPackageName
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller

    private val scopeClient: OkHttpClient = Injekt.get<NetworkHelper>().clientFor(ProxyScope.EXTENSION_DOWNLOAD)

    // SY --> 下载可以并发，安装必须排队：下载完的任务丢进这里，只有下面这一个消费者会真正去装
    private val installQueue = Channel<InstallTask>(Channel.UNLIMITED)

    // 正在等待安装回执的任务（安装结果由 Activity / Installer 回调 updateInstallStep 送回来）
    private val installResults = ConcurrentHashMap<Long, CompletableDeferred<InstallStep>>()

    private data class InstallTask(
        val downloadId: Long,
        val pkgName: String,
        val tempFile: File,
        val step: MutableStateFlow<InstallStep>,
    )

    init {
        scope.launch {
            for (task in installQueue) {
                runCatching { installSerially(task) }
                    .onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to install extension" }
                        task.step.value = InstallStep.Error
                    }
            }
        }
    }

    /**
     * 一次只装一个：上一个**真正装到系统里**之后才轮到下一个。
     */
    private suspend fun installSerially(task: InstallTask) {
        // 这个任务所属的下载流已经结束了（用户取消 / 离开页面）→ 不再装它
        // 注意别删文件：同一插件可能已经重新下载、正用着同一个缓存路径
        if (activeSteps[task.downloadId] !== task.step) return

        task.step.value = InstallStep.Installing

        // SY --> root 静默安装 / 私有安装都是同步出结果的，不需要再等安装器回执和系统广播
        val immediate = installApk(task.downloadId, task.tempFile)
        if (immediate != null) {
            task.step.value = immediate
            return
        }
        // SY <--

        val result = CompletableDeferred<InstallStep>()
        installResults[task.downloadId] = result
        // 先挂上「系统真的装完了」的监听，再动手安装，免得漏掉广播
        val installedSignal = scope.async { awaitPackageChanged(task.pkgName) }
        try {
            Log.e(TAG, "install start: ${task.pkgName}")
            installApk(task.downloadId, task.tempFile)
            // 万一安装器异常退出、回执一直不来，超时后也得放行，否则整条队列会卡死
            val callback = withTimeoutOrNull(INSTALL_TIMEOUT) { result.await() } ?: InstallStep.Error
            Log.e(TAG, "installer callback: ${task.pkgName} -> $callback")

            // 安装器的回执不可信：有的（如 InstallerX 的 root 会话）收到意图就先回 RESULT_OK，
            // 真正的安装还在后台跑；也有的直接回 CANCELED 却照样装上了。
            // 所以还要以「系统把这个包装上/换掉」的广播为准，才能确定这一轮真的结束了。
            // 回执说成功 → 死等真装上；回执说取消/失败 → 只留一小段观察期，别把用户真取消也拖住。
            val waitForRealInstall = if (callback == InstallStep.Installed) {
                INSTALL_REAL_WAIT
            } else {
                INSTALL_GRACE_WAIT
            }
            val reallyInstalled = withTimeoutOrNull(waitForRealInstall) { installedSignal.await() } ?: false
            Log.e(TAG, "package changed: ${task.pkgName} -> $reallyInstalled")

            val terminal = if (reallyInstalled) InstallStep.Installed else callback
            task.step.value = terminal
            Log.e(TAG, "install done: ${task.pkgName} -> $terminal")
        } finally {
            installedSignal.cancel()
            installResults.remove(task.downloadId)
        }
    }

    /**
     * 等系统把 [pkgName] 装上 / 更新掉的广播（安装器回执不可信时用它判断真正的完成点）。
     */
    private suspend fun awaitPackageChanged(pkgName: String): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.data?.encodedSchemeSpecificPart != pkgName) return
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(true)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }
    // SY <--

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: Extension): Flow<InstallStep> {
        val downloadId = extension.pkgName.hashCode().toLong()
        cancelInstall(extension.pkgName)

        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "extension_${extension.pkgName}.apk")
            try {
                step.value = InstallStep.Downloading
                val request = Request.Builder().url(url).build()
                // Extension APK downloads follow the「插件下载更新」proxy scope:
                // 勾选则走内置代理，未勾选（默认）直连。
                val response = scopeClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Failed to download extension (HTTP ${response.code})")
                }
                response.body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // SY --> 下载完成不再直接安装，先排队；真正安装由 installSerially 串行执行
                step.value = InstallStep.Pending
                installQueue.send(InstallTask(downloadId, extension.pkgName, tmpFile, step))
                // SY <--
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    // Canceled
                } else {
                    logcat(LogPriority.ERROR, e) { "Failed to download extension from $url" }
                    step.value = InstallStep.Error
                }
            }
        }

        activeJobs[extension.pkgName] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(extension.pkgName)
                activeSteps.remove(downloadId)
                job.cancel()
            }
    }

    /**
     * 启动安装。
     *
     * @return 已经是确定结果时返回对应 [InstallStep]（root 静默安装 / 私有安装），
     * 需要等安装器回执时返回 null。
     */
    private fun installApk(downloadId: Long, tempFile: File): InstallStep? {
        when (val installer = extensionInstaller.get()) {
            // SY --> root 静默安装：直接 su -c pm install，全程不弹任何界面
            BasePreferences.ExtensionInstaller.ROOT -> {
                val result = RootInstaller.install(tempFile, tempFile.name)
                if (result.success) tempFile.delete()
                return if (result.success) InstallStep.Installed else InstallStep.Error
            }
            // SY <--
            // SY --> INSTALLERX 复用 LEGACY 这套 Activity：既能拿到安装结果，也会在结束时清理临时文件
            BasePreferences.ExtensionInstaller.LEGACY,
            BasePreferences.ExtensionInstaller.INSTALLERX -> {
                // SY <--
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // SY --> 指定目标安装器，避免系统再弹出安装器选择框
                if (installer == BasePreferences.ExtensionInstaller.INSTALLERX) {
                    // 包名按设备实际安装的那个来，不能写死：InstallerX Revived 存在
                    // com.rosan.installer.x 与 com.rosan.installer.x.revived 两种发布包名。
                    // 取不到就退回首选包名，让后续 resolve 失败走 toast 分支而不是静默发错目标。
                    intent.putExtra(
                        EXTRA_TARGET_PACKAGE,
                        context.installerXPackageName ?: INSTALLERX_PACKAGE_NAME,
                    )
                }
                // SY <--

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val installed = try {
                    ExtensionLoader.installPrivateExtensionFile(context, tempFile)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    false
                }

                tempFile.delete()
                return if (installed) InstallStep.Installed else InstallStep.Error
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
        return null
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, pkgName.hashCode().toLong())
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        activeSteps[downloadId]?.let { it.value = step }
        // SY --> 安装到终态了，放行队列里的下一个
        if (step.isCompleted()) {
            installResults[downloadId]?.complete(step)
        }
        // SY <--
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
        // SY --> 指定把安装意图直接发给这个包，而不是交给系统去选择安装器
        const val EXTRA_TARGET_PACKAGE = "ExtensionInstaller.extra.TARGET_PACKAGE"

        // 单个插件等安装器回执的上限，超时就当失败、放行下一个
        private val INSTALL_TIMEOUT = 3.minutes
        // 回执说成功后，等「系统真的装上了」的上限
        private val INSTALL_REAL_WAIT = 2.minutes
        // 回执说取消/失败后，仍给一小段观察期（有的安装器会先回 CANCELED 再默默装完）
        private val INSTALL_GRACE_WAIT = 15.seconds

        private const val TAG = "SYExtInstall"
        // SY <--
    }
}
