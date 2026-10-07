package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments.repository)

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        // SY -->
        val isNewVersion =
            isNewVersion(arguments.isPreview, arguments.syDebugVersion, arguments.versionName, release.version)
        // SY <--
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    // SY -->
    private fun isNewVersion(
        isPreview: Boolean,
        syDebugVersion: String,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "jobobby04/TachiyomiXPreview" repo
            // tagged as something like "508"
            val currentInt = syDebugVersion.toIntOrNull()
            val newInt = newVersion.split(".").firstOrNull()?.toIntOrNull()
            currentInt != null && newInt != null && newInt > currentInt
        } else {
            // Release builds: based on releases in "jobobby04/TachiyomiX" repo
            // tagged as something like "0.1.2"
            compareVersion(newVersion, versionName) > 0
        }
    }

    /**
     * 逐段比较版本号：a > b 返回正数、相等 0、a < b 负数（段数不同时短的按 0 补齐）。
     *
     * 旧写法是「按旧版本的下标去取新版本」，有两个坑：
     * ① 段数更少的 tag（新版本 `1.1`、本机 `1.0.1`）会 IndexOutOfBounds，被上层 catch 成「检测更新失败」；
     * ② 只要任意一段更大就算有新版 → `1.5.0` 会被判成比 `2.0.0` 新，给用户推"降级更新"。
     */
    private fun compareVersion(a: String, b: String): Int {
        val aParts = a.stripVersionPrefix().split(".").mapNotNull { it.toIntOrNull() }
        val bParts = b.stripVersionPrefix().split(".").mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(aParts.size, bParts.size)) {
            val diff = aParts.getOrElse(index) { 0 } - bParts.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    /** 去掉 `v` / `r` 这类前缀，只留数字与点（调用方可能传进来的是 `v1.0.1`）。 */
    private fun String.stripVersionPrefix(): String = replace("[^\\d.]".toRegex(), "")
    // SY <--

    data class Arguments(
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        // SY -->
        val syDebugVersion: String,
        // SY <--
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
