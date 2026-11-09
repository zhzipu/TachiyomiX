package eu.kanade.tachiyomi.util.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R

/**
 * 伪装应用：通过启用/禁用 activity-alias 切换桌面显示的图标与名称。
 *
 * 别名在 AndroidManifest 中声明，只影响桌面入口；应用内的 Activity、包名与真实应用名均不变。
 * 组件名由 applicationId 与别名类名组成，因此各 flavor（含后缀）都能正确定位。
 *
 * 计算器 / 记事本 / 学习助手三种身份下各有 [VARIANTS] 套图标，别名与图标资源一一对应（见清单）。
 */
object LauncherDisguise {

    /** 默认入口（TachiyomiX 图标与名称） */
    const val DEFAULT = "eu.kanade.tachiyomi.LauncherDefault"

    /** 伪装身份，每种身份下有多套图标可选 */
    enum class Type(val aliasPrefix: String) {
        Calculator("eu.kanade.tachiyomi.LauncherFakeCalc"),
        Notebook("eu.kanade.tachiyomi.LauncherFakeNote"),
        Study("eu.kanade.tachiyomi.LauncherFakeStudy"),
    }

    /** 每种身份可选的图标套数 */
    const val VARIANTS = 5

    /**
     * 一套桌面外观。
     *
     * @param type 所属身份，默认入口为 null
     * @param variant 同身份内的序号（1..[VARIANTS]），默认入口为 0
     * @param previewRes 设置页预览用的位图。桌面图标是自适应图标（adaptive-icon XML），
     *   painterResource 加载不了，所以这里指向同名的底层图片资源。
     */
    data class Skin(
        val alias: String,
        val type: Type?,
        val variant: Int,
        @DrawableRes val previewRes: Int?,
    )

    /** 全部桌面入口：默认入口 → 计算器 1..5 → 记事本 1..5 → 学习助手 1..5 */
    val skins: List<Skin> = listOf(
        Skin(DEFAULT, null, 0, null),

        Skin(Type.Calculator.aliasPrefix + 1, Type.Calculator, 1, R.drawable.disguise_calc_1),
        Skin(Type.Calculator.aliasPrefix + 2, Type.Calculator, 2, R.drawable.disguise_calc_2),
        Skin(Type.Calculator.aliasPrefix + 3, Type.Calculator, 3, R.drawable.disguise_calc_3),
        Skin(Type.Calculator.aliasPrefix + 4, Type.Calculator, 4, R.drawable.disguise_calc_4),
        Skin(Type.Calculator.aliasPrefix + 5, Type.Calculator, 5, R.drawable.disguise_calc_5),

        Skin(Type.Notebook.aliasPrefix + 1, Type.Notebook, 1, R.drawable.disguise_note_1),
        Skin(Type.Notebook.aliasPrefix + 2, Type.Notebook, 2, R.drawable.disguise_note_2),
        Skin(Type.Notebook.aliasPrefix + 3, Type.Notebook, 3, R.drawable.disguise_note_3),
        Skin(Type.Notebook.aliasPrefix + 4, Type.Notebook, 4, R.drawable.disguise_note_4),
        Skin(Type.Notebook.aliasPrefix + 5, Type.Notebook, 5, R.drawable.disguise_note_5),

        Skin(Type.Study.aliasPrefix + 1, Type.Study, 1, R.drawable.disguise_study_1),
        Skin(Type.Study.aliasPrefix + 2, Type.Study, 2, R.drawable.disguise_study_2),
        Skin(Type.Study.aliasPrefix + 3, Type.Study, 3, R.drawable.disguise_study_3),
        Skin(Type.Study.aliasPrefix + 4, Type.Study, 4, R.drawable.disguise_study_4),
        Skin(Type.Study.aliasPrefix + 5, Type.Study, 5, R.drawable.disguise_study_5),
    )

    /** 全部别名，顺序与 [skins] 一致。 */
    val aliases: List<String> = skins.map { it.alias }

    /** 旧版本每种身份只有一套图标，存下的别名迁移到该身份的第 1 套。 */
    private val legacyAliases = mapOf(
        "eu.kanade.tachiyomi.LauncherFakeCalc" to Type.Calculator.aliasPrefix + 1,
        "eu.kanade.tachiyomi.LauncherFakeNote" to Type.Notebook.aliasPrefix + 1,
        "eu.kanade.tachiyomi.LauncherFakeStudy" to Type.Study.aliasPrefix + 1,
    )

    /** 把可能为空、旧版或已失效的偏好值归一化为一个合法别名。 */
    fun normalize(alias: String?): String {
        val migrated = legacyAliases[alias] ?: alias
        return migrated?.takeIf { it in aliases } ?: DEFAULT
    }

    /** 取别名对应的外观，无法识别时回落到默认入口。 */
    fun skinOf(alias: String?): Skin = skins.firstOrNull { it.alias == normalize(alias) } ?: skins.first()

    /** 某身份下的全部图标选项。 */
    fun skinsOf(type: Type): List<Skin> = skins.filter { it.type == type }

    /**
     * 应用指定的桌面入口：先启用目标别名，再禁用其余别名，避免出现短暂没有桌面图标的空档。
     */
    fun apply(context: Context, alias: String) {
        val target = normalize(alias)
        val packageManager = context.packageManager
        setEnabled(packageManager, target, true)
        // 旧版别名也一并关掉（它不在 skins 里，但可能还留着老用户的「已启用」状态）
        (aliases + legacyAliases.keys).filterNot { it == target }
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
