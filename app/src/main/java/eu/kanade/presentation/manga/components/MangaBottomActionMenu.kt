package eu.kanade.presentation.manga.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.isTabletUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun MangaBottomActionMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onBookmarkClicked: (() -> Unit)? = null,
    onRemoveBookmarkClicked: (() -> Unit)? = null,
    onMarkAsReadClicked: (() -> Unit)? = null,
    onMarkAsUnreadClicked: (() -> Unit)? = null,
    onMarkPreviousAsReadClicked: (() -> Unit)? = null,
    onDownloadClicked: (() -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm = remember { mutableStateListOf(false, false, false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            Row(
                modifier = Modifier
                    .padding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues(),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                if (onBookmarkClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_bookmark),
                        icon = Icons.Outlined.BookmarkAdd,
                        toConfirm = confirm[0],
                        onLongClick = { onLongClickItem(0) },
                        onClick = onBookmarkClicked,
                    )
                }
                if (onRemoveBookmarkClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_remove_bookmark),
                        icon = Icons.Outlined.BookmarkRemove,
                        toConfirm = confirm[1],
                        onLongClick = { onLongClickItem(1) },
                        onClick = onRemoveBookmarkClicked,
                    )
                }
                if (onMarkAsReadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_mark_as_read),
                        icon = Icons.Outlined.DoneAll,
                        toConfirm = confirm[2],
                        onLongClick = { onLongClickItem(2) },
                        onClick = onMarkAsReadClicked,
                    )
                }
                if (onMarkAsUnreadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_mark_as_unread),
                        icon = Icons.Outlined.RemoveDone,
                        toConfirm = confirm[3],
                        onLongClick = { onLongClickItem(3) },
                        onClick = onMarkAsUnreadClicked,
                    )
                }
                if (onMarkPreviousAsReadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_mark_previous_as_read),
                        icon = ImageVector.vectorResource(R.drawable.ic_done_prev_24dp),
                        toConfirm = confirm[4],
                        onLongClick = { onLongClickItem(4) },
                        onClick = onMarkPreviousAsReadClicked,
                    )
                }
                if (onDownloadClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = onDownloadClicked,
                    )
                }
                if (onDeleteClicked != null) {
                    Button(
                        title = stringResource(MR.strings.action_delete),
                        icon = Icons.Outlined.Delete,
                        toConfirm = confirm[6],
                        onLongClick = { onLongClickItem(6) },
                        onClick = onDeleteClicked,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.Button(
    title: String,
    icon: ImageVector,
    toConfirm: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    // SY -->
    // 置灰的按钮：仍然占位、仍然显示图标，但不响应点击，也不参与「长按展开文字」
    enabled: Boolean = true,
    // SY <--
    content: (@Composable () -> Unit)? = null,
) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (toConfirm) 2f else 1f,
        label = "weight",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(animatedWeight)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false),
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.alpha(if (enabled) 1f else BottomBarDisabledAlpha),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
            )
            AnimatedVisibility(
                visible = toConfirm,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Text(
                    text = title,
                    overflow = TextOverflow.Visible,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        content?.invoke()
    }
}

@Composable
fun LibraryBottomActionMenu(
    visible: Boolean,
    onChangeCategoryClicked: () -> Unit,
    onMarkAsReadClicked: () -> Unit,
    onMarkAsUnreadClicked: () -> Unit,
    onDownloadClicked: ((DownloadAction) -> Unit)?,
    // SY -->
    // 「下载」分类里这个位置换成「上传」：把所选漫画已下载的章节传到网络图源的 WebDAV 库。
    // 两者互斥，由调用方（`LibraryTab`）按当前分类决定传哪一个进来。
    onUploadClicked: (() -> Unit)?,
    // 上传按钮是否可用（网络图源没配置服务器信息时置灰）
    uploadEnabled: Boolean = true,
    // SY <--
    onDeleteClicked: () -> Unit,
    onMigrateClicked: (() -> Unit)?,
    // SY -->
    onClickCleanTitles: (() -> Unit)?,
    onClickCollectRecommendations: (() -> Unit)?,
    onClickAddToMangaDex: (() -> Unit)?,
    onClickResetInfo: (() -> Unit)?,
    // SY <--
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm = remember { mutableStateListOf(false, false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            // SY -->
            val showOverflow = onClickCleanTitles != null ||
                onClickAddToMangaDex != null ||
                onClickResetInfo != null ||
                onClickCollectRecommendations != null ||
                onMigrateClicked != null
            val configuration = LocalConfiguration.current
            val moveMarkPrev = remember { !configuration.isTabletUi() }
            var overFlowOpen by remember { mutableStateOf(false) }
            // SY <--
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Button(
                    title = stringResource(MR.strings.action_move_category),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    toConfirm = confirm[0],
                    onLongClick = { onLongClickItem(0) },
                    onClick = onChangeCategoryClicked,
                )
                // SY -->
                // 同一个位置只放一个按钮：普通分类是「下载」下拉菜单，书架「下载」分类是「上传」。
                // 两者都用 confirm[3] 这个槽位，长按展开文字行为保持一致。
                if (onDownloadClicked != null) {
                    var downloadExpanded by remember { mutableStateOf(false) }
                    Button(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[3],
                        onLongClick = { onLongClickItem(3) },
                        onClick = { downloadExpanded = !downloadExpanded },
                    ) {
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = { downloadExpanded = false },
                            onDownloadClicked = onDownloadClicked,
                            offset = BottomBarMenuDpOffset,
                        )
                    }
                } else if (onUploadClicked != null) {
                    Button(
                        // 「拖入队列」是文案上的名字 —— 点它**不会立刻开始上传**，
                        // 而是把所选漫画已下载的章节按话排进上传队列，
                        // 用户在「更多 → 上传队列」里能看到、能取消、能重排。
                        title = stringResource(SYMR.strings.action_add_to_upload_queue),
                        icon = Icons.Outlined.Upload,
                        toConfirm = confirm[3],
                        onLongClick = { onLongClickItem(3) },
                        onClick = onUploadClicked,
                        // 网络图源没配置服务器信息时置灰，点了也没有意义
                        enabled = uploadEnabled,
                    )
                }
                // SY <--
                Button(
                    title = stringResource(MR.strings.action_delete),
                    icon = Icons.Outlined.Delete,
                    toConfirm = confirm[4],
                    onLongClick = { onLongClickItem(4) },
                    onClick = onDeleteClicked,
                )
                // SY -->
                Button(
                    title = stringResource(MR.strings.action_mark_as_read),
                    icon = Icons.Outlined.DoneAll,
                    toConfirm = confirm[1],
                    onLongClick = { onLongClickItem(1) },
                    onClick = onMarkAsReadClicked,
                )
                if (showOverflow) {
                    if (!moveMarkPrev) {
                        Button(
                            title = stringResource(MR.strings.action_mark_as_unread),
                            icon = Icons.Outlined.RemoveDone,
                            toConfirm = confirm[2],
                            onLongClick = { onLongClickItem(2) },
                            onClick = onMarkAsUnreadClicked,
                        )
                    }
                    Button(
                        title = stringResource(MR.strings.label_more),
                        icon = Icons.Outlined.MoreVert,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = { overFlowOpen = true },
                    )
                    DropdownMenu(
                        expanded = overFlowOpen,
                        onDismissRequest = { overFlowOpen = false },
                    ) {
                        if (moveMarkPrev) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_mark_as_unread)) },
                                onClick = onMarkAsUnreadClicked,
                            )
                        }
                        if (onClickCleanTitles != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(SYMR.strings.action_clean_titles)) },
                                onClick = onClickCleanTitles,
                            )
                        }
                        if (onMigrateClicked != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.migrate)) },
                                onClick = onMigrateClicked,
                            )
                        }
                        if (onClickCollectRecommendations != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(SYMR.strings.rec_search_short)) },
                                onClick = onClickCollectRecommendations,
                            )
                        }
                        if (onClickAddToMangaDex != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(SYMR.strings.mangadex_add_to_follows)) },
                                onClick = onClickAddToMangaDex,
                            )
                        }
                        if (onClickResetInfo != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(SYMR.strings.reset_info)) },
                                onClick = onClickResetInfo,
                            )
                        }
                    }
                } else {
                    Button(
                        title = stringResource(MR.strings.action_mark_as_unread),
                        icon = Icons.Outlined.RemoveDone,
                        toConfirm = confirm[2],
                        onLongClick = { onLongClickItem(2) },
                        onClick = onMarkAsUnreadClicked,
                    )
                }
                // SY <--
            }
        }
    }
}

private val BottomBarMenuDpOffset = DpOffset(0.dp, 0.dp)

/** 底栏按钮「不可用」时的整体透明度，与 Material3 的禁用态一致。 */
private const val BottomBarDisabledAlpha = 0.38f
