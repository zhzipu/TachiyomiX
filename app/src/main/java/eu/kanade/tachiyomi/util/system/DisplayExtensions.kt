package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.TabletUiMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val TABLET_UI_REQUIRED_SCREEN_WIDTH_DP = 720

/**
 * 平板（大屏）界面要求的窗口最小边。
 *
 * 书本式折叠屏展开后内屏的最小边通常在 600~900dp 之间（竖屏展开时最小边是宽度），
 * 收起到外屏后只有 380~430dp；普通手机竖屏最小边是宽度、横屏最小边也是宽度，
 * 都到不了 600dp。所以用「窗口最小边」一个条件就能区分：展开→平板界面，收起→手机界面。
 */
const val TABLET_UI_MIN_WINDOW_SIZE_DP = 600

/**
 * resources 里自带的那份配置是否已经算作平板宽度（只看未经改写的 smallestScreenWidthDp）。
 */
private fun Configuration.resourceReportsTabletUi(): Boolean {
    return smallestScreenWidthDp >= TABLET_UI_REQUIRED_SCREEN_WIDTH_DP
}

/**
 * 窗口最小边是否达到大屏宽度。
 *
 * 不用 `smallestScreenWidthDp`，因为它是「物理最小边」，折叠屏展开后接近正方形时
 * 它并不随折叠状态变化；窗口宽高则一定随折叠/展开更新。
 */
fun isLargeWindow(widthDp: Int, heightDp: Int): Boolean {
    return minOf(widthDp, heightDp) >= TABLET_UI_MIN_WINDOW_SIZE_DP
}

fun Configuration.isTabletUi(mode: TabletUiMode): Boolean {
    return when (mode) {
        // 自动：按当前窗口尺寸判断，折叠屏收起/展开时随窗口大小切换
        TabletUiMode.AUTOMATIC -> isLargeWindow(screenWidthDp, screenHeightDp)
        TabletUiMode.ALWAYS -> true
        TabletUiMode.LANDSCAPE -> orientation == Configuration.ORIENTATION_LANDSCAPE
        TabletUiMode.NEVER -> false
    }
}

fun Configuration.isTabletUi(): Boolean {
    return isTabletUi(Injekt.get<UiPreferences>().tabletUiMode.get())
}

// TODO: move the logic to `isTabletUi()` when main activity is rewritten in Compose
fun Context.prepareTabletUiContext(): Context {
    val configuration = resources.configuration
    val expected = configuration.isTabletUi()
    if (configuration.resourceReportsTabletUi() != expected) {
        val overrideConf = Configuration()
        overrideConf.setTo(configuration)
        overrideConf.smallestScreenWidthDp = if (expected) {
            overrideConf.smallestScreenWidthDp.coerceAtLeast(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP)
        } else {
            overrideConf.smallestScreenWidthDp.coerceAtMost(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP - 1)
        }
        return createConfigurationContext(overrideConf)
    }
    return this
}

/**
 * Returns true if current context is in night mode
 */
fun Context.isNightMode(): Boolean {
    return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun Activity.hasDisplayCutout(): Boolean {
    return window.decorView.hasDisplayCutout()
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun View.hasDisplayCutout(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && rootWindowInsets?.displayCutout != null
}

/**
 * Gets system's config_navBarNeedsScrim boolean flag added in Android 10, defaults to true.
 */
fun Context.isNavigationBarNeedsScrim(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        InternalResourceHelper.getBoolean(this, "config_navBarNeedsScrim", true)
}
