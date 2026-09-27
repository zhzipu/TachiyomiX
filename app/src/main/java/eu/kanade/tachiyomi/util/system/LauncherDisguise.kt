package eu.kanade.tachiyomi.util.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.BuildConfig

/**
 * 伪装应用：通过启用/禁用 activity-alias 切换桌面显示的图标与名称。
 *
 * 别名在 AndroidManifest 中声明，只影响桌面入口；应用内的 Activity、包名与真实应用名均不变。
 * 组件名由 applicationId 与别名类名组成，因此各 flavor（含后缀）都能正确定位。
 */
object LauncherDisguise {

    /** 默认入口（TachiyomiX 图标与名称） */
    const val DEFAULT = "eu.kanade.tachiyomi.LauncherDefault"

    const val FAKE_CALC = "eu.kanade.tachiyomi.LauncherFakeCalc"
    const val FAKE_NOTE = "eu.kanade.tachiyomi.LauncherFakeNote"
    const val FAKE_STUDY = "eu.kanade.tachiyomi.LauncherFakeStudy"

    /** 全部别名，顺序与设置页展示顺序一致。 */
    val aliases = listOf(DEFAULT, FAKE_CALC, FAKE_NOTE, FAKE_STUDY)

    /** 把可能为空或已失效的偏好值归一化为一个合法别名。 */
    fun normalize(alias: String?): String = alias?.takeIf { it in aliases } ?: DEFAULT

    /**
     * 应用指定的桌面入口：先启用目标别名，再禁用其余别名，避免出现短暂没有桌面图标的空档。
     */
    fun apply(context: Context, alias: String) {
        val target = normalize(alias)
        val packageManager = context.packageManager
        setEnabled(packageManager, target, true)
        aliases.filterNot { it == target }
            .forEach { setEnabled(packageManager, it, false) }
    }

    private fun setEnabled(packageManager: PackageManager, alias: String, enabled: Boolean) {
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(BuildConfig.APPLICATION_ID, alias),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
