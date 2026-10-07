package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import exh.syDebugVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val result = getApplicationRelease.awaitWithRetry(
                GetApplicationRelease.Arguments(
                    // SY -->
                    isPreviewBuildType,
                    // SY <--
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    // SY -->
                    syDebugVersion,
                    // SY <--
                    forceCheck,
                ),
            )

            when (result) {
                is GetApplicationRelease.Result.NewUpdate -> AppUpdateNotifier(context).promptUpdate(result.release)
                else -> {}
            }

            result
        }
    }
}

val GITHUB_REPO: String by lazy {
    "zhzipu/TachiyomiX"
}

/** 更新检查的退避重试次数与首次等待时长。 */
private const val UPDATE_CHECK_ATTEMPTS = 4
private const val UPDATE_CHECK_FIRST_DELAY_MS = 500L

/**
 * [GetApplicationRelease.await] 的健壮版：只对「连不上 / 超时」这类 [IOException] 退避重试。
 *
 * 为什么需要：应用启动时 `App` 是在后台协程里 `ClashManager.restart()` 起内置 mihomo 的
 * （`App.kt` 启动块），而所有请求都经 `ClashProxySelector`（见 `NetworkHelper`）走那个 127.0.0.1 端口 ——
 * 端口还没开始监听时，「刚开应用就检测更新」必定 ConnectException，等几秒才恢复。
 * 4xx / 解析错误不重试（重试没有意义，也避免给 GitHub 白刷请求）。
 */
suspend fun GetApplicationRelease.awaitWithRetry(
    arguments: GetApplicationRelease.Arguments,
    attempts: Int = UPDATE_CHECK_ATTEMPTS,
): GetApplicationRelease.Result {
    var delayMs = UPDATE_CHECK_FIRST_DELAY_MS
    repeat(attempts) { index ->
        try {
            return await(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (index == attempts - 1) throw e
            logcat(LogPriority.WARN, e) {
                "Update check failed (${index + 1}/$attempts), retrying in ${delayMs}ms"
            }
            delay(delayMs)
            delayMs *= 2
        }
    }
    error("unreachable")
}
