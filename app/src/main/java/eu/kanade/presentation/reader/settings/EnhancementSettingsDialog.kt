package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel

/**
 * 阅读器底栏「增强设置」按钮打开的设置面板。
 *
 * 复用阅读器既有的底部弹窗/对话框形态（[AdaptiveSheet]），内容与
 * 自定义滤镜标签页里的增强设置块完全一致（见 [EnhancementSettingsPage]），
 * 只有总开关仍留在滤镜页。
 */
@Composable
fun EnhancementSettingsDialog(
    onDismissRequest: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight * 0.8f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = TabbedDialogPaddings.Vertical),
            ) {
                EnhancementSettingsPage(screenModel)
            }
        }
    }
}
