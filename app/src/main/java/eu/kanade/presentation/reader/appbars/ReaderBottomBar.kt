package eu.kanade.presentation.reader.appbars

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

// 单个按钮的最小宽度，用于判断一排是否放得下
private val MIN_BUTTON_WIDTH = 48.dp

// 大屏判定宽度（横屏、平板等宽屏设备）
private val LARGE_SCREEN_WIDTH = 600.dp

@Composable
fun ReaderBottomBar(
    // SY -->
    enabledButtons: Set<String>,
    // SY <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    onLongClickShiftPage: (() -> Unit)? = null,
    onClickImageEnhancement: () -> Unit,
    imageEnhancementEnabled: Boolean,
    enhancementAvailable: Boolean,
    onClickEnhancementSettings: () -> Unit,
    spatialSceneActive: Boolean,
    spatialSceneBusy: Boolean,
    onClickSpatialScene: () -> Unit,
    // SY <--
    modifier: Modifier = Modifier,
) {
    // 图标统一样式：启用时用 onSurface 着色，未开启时半透明置灰
    val iconTint = MaterialTheme.colorScheme.onSurface
    val inactiveTint = iconTint.copy(alpha = 0.6f)

    // SY -->
    val cropBorders = when (currentReadingMode) {
        ReadingMode.WEBTOON -> ReaderBottomButton.CropBordersWebtoon
        ReadingMode.CONTINUOUS_VERTICAL -> ReaderBottomButton.CropBordersContinuesVertical
        else -> ReaderBottomButton.CropBordersPager
    }

    // 双页布局是否适用：只有分页阅读模式且有双页布局时，双页切换才有作用
    val doublePageApplicable = ReadingMode.isPagerType(currentReadingMode.flagValue) && doublePages

    // 第一排：阅读模式 - 章节 - 裁剪 - 页面布局 - 双页切换
    val topRow: List<@Composable () -> Unit> = buildList {
        // 阅读模式固定在第一位，不受「底部按钮」配置影响
        add {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                    tint = iconTint,
                )
            }
        }

        if (ReaderBottomButton.ViewChapters.isIn(enabledButtons)) {
            add {
                IconButton(onClick = onClickChapterList) {
                    Icon(
                        imageVector = Icons.Outlined.FormatListNumbered,
                        contentDescription = stringResource(MR.strings.chapters),
                        tint = iconTint,
                    )
                }
            }
        }

        if (cropBorders.isIn(enabledButtons)) {
            add {
                IconButton(onClick = onClickCropBorder) {
                    Icon(
                        painter = painterResource(
                            if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                        ),
                        contentDescription = stringResource(MR.strings.pref_crop_borders),
                        // 裁剪未开启时置灰
                        tint = if (cropEnabled) iconTint else inactiveTint,
                    )
                }
            }
        }

        if (
            !dualPageSplitEnabled &&
            ReaderBottomButton.PageLayout.isIn(enabledButtons) &&
            ReadingMode.isPagerType(currentReadingMode.flagValue)
        ) {
            add {
                IconButton(onClick = onClickPageLayout) {
                    Icon(
                        painter = painterResource(R.drawable.ic_page_24dp),
                        contentDescription = stringResource(SYMR.strings.page_layout),
                        tint = iconTint,
                    )
                }
            }
        }

        // 双页切换：始终显示；仅分页阅读模式下的双页布局才有意义，其余情况置灰不可点
        // 长按 = 反转双页左右顺序（1|2 <-> 2|1）
        add {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .combinedClickable(
                        enabled = doublePageApplicable,
                        onClick = onClickShiftPage,
                        onLongClick = if (doublePageApplicable) onLongClickShiftPage else null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_page_next_outline_24dp),
                    contentDescription = stringResource(SYMR.strings.shift_double_pages),
                    tint = if (doublePageApplicable) iconTint else inactiveTint,
                )
            }
        }
    }

    // 第二排：浏览器打开 - 网页打开 - 空间深度模型 - 图像增强 - 增强设置 - 设置
    val bottomRow: List<@Composable () -> Unit> = buildList {
        if (ReaderBottomButton.Browser.isIn(enabledButtons) && onClickBrowser != null) {
            add {
                IconButton(onClick = onClickBrowser) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = stringResource(MR.strings.action_open_in_browser),
                        tint = iconTint,
                    )
                }
            }
        }

        if (ReaderBottomButton.WebView.isIn(enabledButtons) && onClickWebView != null) {
            add {
                IconButton(onClick = onClickWebView) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = stringResource(MR.strings.action_open_in_web_view),
                        tint = iconTint,
                    )
                }
            }
        }

        // 空间深度模型：正在生成或已开启时高亮，其余情况置灰（生成过程中禁用点击避免重复触发）
        add {
            IconButton(onClick = onClickSpatialScene, enabled = !spatialSceneBusy) {
                Icon(
                    painter = painterResource(R.drawable.ic_view_in_ar_24dp),
                    contentDescription = stringResource(MR.strings.reader_spatial_scene),
                    tint = if (spatialSceneActive || spatialSceneBusy) iconTint else inactiveTint,
                )
            }
        }

        add {
            IconButton(
                onClick = onClickImageEnhancement,
                enabled = enhancementAvailable,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_photo_24dp),
                    contentDescription = stringResource(MR.strings.reader_image_enhancement),
                    // 图像增强未开启或无可用模型时置灰
                    tint = if (imageEnhancementEnabled && enhancementAvailable) iconTint else inactiveTint,
                )
            }
        }

        // 增强设置：与「图像增强」开关相邻，打开只包含增强项的设置面板
        add {
            IconButton(
                onClick = onClickEnhancementSettings,
                enabled = enhancementAvailable,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ai_enhance_24dp),
                    contentDescription = stringResource(MR.strings.reader_enhancement_settings),
                    // 与其他底栏图标一致：跟随主题 onSurface 着色，无可用模型时置灰
                    tint = if (enhancementAvailable) iconTint else inactiveTint,
                )
            }
        }

        add {
            IconButton(onClick = onClickSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(MR.strings.action_settings),
                    tint = iconTint,
                )
            }
        }

        // 其余可选按钮排在本排末尾
        if (ReaderBottomButton.Rotation.isIn(enabledButtons)) {
            add {
                IconButton(onClick = onClickOrientation) {
                    Icon(
                        imageVector = orientation.icon,
                        contentDescription = stringResource(MR.strings.pref_rotation_type),
                        tint = iconTint,
                    )
                }
            }
        }

        if (ReaderBottomButton.Share.isIn(enabledButtons) && onClickShare != null) {
            add {
                IconButton(onClick = onClickShare) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(MR.strings.action_share),
                        tint = iconTint,
                    )
                }
            }
        }
    }

    // 横屏、平板或大屏设备且一排放得下时用单排显示，否则分两排
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = isTabletUi()

    BoxWithConstraints(modifier = modifier) {
        val totalCount = topRow.size + bottomRow.size
        val singleRow = (isLandscape || isTablet || maxWidth >= LARGE_SCREEN_WIDTH) &&
            MIN_BUTTON_WIDTH * totalCount <= maxWidth
        if (singleRow) {
            BottomBarRow(topRow + bottomRow)
        } else {
            Column {
                BottomBarRow(topRow)
                BottomBarRow(bottomRow)
            }
        }
    }
    // SY <--
}

@Composable
private fun BottomBarRow(buttons: List<@Composable () -> Unit>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEach { it() }
    }
}
