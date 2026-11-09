package eu.kanade.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.tachiyomi.util.system.TABLET_UI_MIN_WINDOW_SIZE_DP
import eu.kanade.tachiyomi.util.system.isTabletUi
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
@ReadOnlyComposable
fun isTabletUi(): Boolean {
    val mode = Injekt.get<UiPreferences>().tabletUiMode.get()
    if (mode != TabletUiMode.AUTOMATIC) {
        return LocalConfiguration.current.isTabletUi(mode)
    }

    // 「自动」按真实窗口尺寸判断：prepareTabletUiContext 会改写 resources 里的
    // smallestScreenWidthDp（那份值在折叠/展开后可能滞后），窗口尺寸则实时跟随折叠状态。
    val containerSize = LocalWindowInfo.current.containerSize
    if (containerSize.width <= 0 || containerSize.height <= 0) {
        return LocalConfiguration.current.isTabletUi(mode)
    }
    val thresholdPx = with(LocalDensity.current) { TABLET_UI_MIN_WINDOW_SIZE_DP.dp.roundToPx() }
    return minOf(containerSize.width, containerSize.height) >= thresholdPx
}
