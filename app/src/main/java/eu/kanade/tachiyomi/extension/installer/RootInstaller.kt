package eu.kanade.tachiyomi.extension.installer

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 用 root 静默安装 APK（`su -c pm install`）。
 *
 * 相比走系统安装器（[eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity] 或 InstallerX），
 * 这条路没有「安装器先回执、系统后台再装」的时序问题——`pm install` 返回时包已经装到系统里，
 * 所以调用方能直接拿到确定结果，不用再等 PACKAGE_ADDED 广播。
 *
 * 首次调用会触发超级用户授权弹窗（Magisk / KernelSU / APatch），期间命令会一直挂着，
 * 所以探测和安装都给了足够长的超时，并且放在后台线程执行。
 */
internal object RootInstaller {

    /** 单次执行的结果，[message] 是给用户看的简短原因。 */
    data class Result(
        val success: Boolean,
        val message: String = "",
    )

    /** 探测 `su` 是否可用的超时：要覆盖用户手动点「允许」的时间。 */
    private val PROBE_TIMEOUT = 60.seconds

    /** 静默安装的超时：大模型包 APK 也要留余量。 */
    private val INSTALL_TIMEOUT = 5.minutes

    /** root 可用性只确认一次；[reset] 用于用户在设置里重新检测。 */
    @Volatile
    private var availability: Boolean? = null

    /**
     * 是否拿得到 root。
     *
     * 第一次调用会真的去执行 `su -c id`（可能弹授权框），之后走缓存。
     */
    fun isRootAvailable(force: Boolean = false): Boolean {
        if (!force) availability?.let { return it }
        val outcome = exec("id", PROBE_TIMEOUT)
        val granted = outcome != null && outcome.exitCode == 0 && outcome.output.contains("uid=0")
        availability = granted
        return granted
    }

    fun reset() {
        availability = null
    }

    /**
     * 静默安装 / 更新 [apk]。
     *
     * 阻塞调用，必须在 IO 线程执行。
     */
    fun install(apk: File, label: String? = null): Result {
        if (!apk.isFile) {
            return Result(false, "APK 文件不存在：${apk.name}")
        }

        val name = label ?: apk.name
        // -r 覆盖安装、-d 允许降级、-t 允许 testOnly 包；--user 0 固定装到机主空间
        val command = "pm install -r -d -t --user 0 ${shellQuote(apk.absolutePath)}"
        val outcome = exec(command, INSTALL_TIMEOUT)
            ?: return Result(false, "未获取到 root 权限（su 不可用）")

        val output = outcome.output.trim()
        val success = outcome.exitCode == 0 &&
            output.contains("Success", ignoreCase = true) &&
            !output.contains("Failure", ignoreCase = true)

        logcat(if (success) LogPriority.INFO else LogPriority.ERROR) {
            "root install $name -> exit=${outcome.exitCode} ${output.replace('\n', ' ').take(300)}"
        }
        // 临时诊断（release 里 logcat 库会被剥掉）
        android.util.Log.e("SYRootInstall", "$name exit=${outcome.exitCode} -> ${output.replace('\n', ' ').take(200)}")

        return Result(success, if (success) "" else summarizeFailure(output))
    }

    /** 把 `Failure [INSTALL_FAILED_XXX]` 抽成一句人话，其它输出原样截断。 */
    private fun summarizeFailure(output: String): String {
        if (output.isBlank()) return "安装失败（无输出）"
        val reason = Regex("Failure \\[([^]]+)]").find(output)?.groupValues?.get(1)
        if (reason != null) return "安装失败：$reason"
        return "安装失败：${output.lineSequence().last { it.isNotBlank() }.take(160)}"
    }

    private class Outcome(val exitCode: Int, val output: String)

    /**
     * 执行一条 root 命令，超时就强杀。
     *
     * @return null 表示 `su` 根本起不来（设备未 root / 没有授权机制）。
     */
    private fun exec(command: String, timeout: Duration): Outcome? {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "su is not available" }
            return null
        }

        // su 不读 stdin，关掉以免某些实现等输入
        runCatching { process.outputStream.close() }

        val output = StringBuilder()
        val pump = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            pump.join(1000)
            logcat(LogPriority.WARN) { "root command timed out: ${command.take(80)}" }
            return Outcome(-1, output.toString())
        }

        pump.join(1000)
        return Outcome(process.exitValue(), output.toString())
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
