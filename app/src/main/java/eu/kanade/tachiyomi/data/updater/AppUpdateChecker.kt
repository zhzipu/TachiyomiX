package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import exh.syDebugVersion
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.interactor.awaitWithRetry
import tachiyomi.domain.release.model.Release
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()

    /**
     * 已经提示过的版本号。
     *
     * 启动时每次都会检测，但同一个新版本只提示一次 —— 否则只要用户不更新，
     * 每次冷启动都会被强行推到「发现新版本」页面。
     */
    private val promptedVersion: Preference<String> by lazy {
        preferenceStore.getString(Preference.appStateKey("app_update_prompted_version"), "")
    }

    /**
     * @param forceCheck 忽略「3 天内已检查过」的节流，启动检测走 true。
     * @param notify 发现新版本时是否发通知。启动检测自己会跳提示页，传 false 免得同时弹通知。
     */
    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
        notify: Boolean = true,
    ): GetApplicationRelease.Result {
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
                is GetApplicationRelease.Result.NewUpdate -> {
                    if (notify) {
                        AppUpdateNotifier(context).promptUpdate(result.release)
                    }
                }
                else -> {}
            }

            result
        }
    }

    /** 这个新版本还没提示过。 */
    fun shouldPrompt(release: Release): Boolean = release.version != promptedVersion.get()

    /** 标记这个新版本已提示过，后续启动不再重复弹出。 */
    fun markPrompted(release: Release) {
        promptedVersion.set(release.version)
    }
}

val GITHUB_REPO: String by lazy {
    "zhzipu/TachiyomiX"
}
