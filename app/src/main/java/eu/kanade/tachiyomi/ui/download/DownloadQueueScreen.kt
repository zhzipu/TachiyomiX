package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { DownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        // SY --> 「暂停」弹窗的显隐
        var showPauseDialog by remember { mutableStateOf(false) }
        var showResumeDialog by remember { mutableStateOf(false) }
        // SY <--
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (downloadCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$downloadCount",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (downloadList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                            }

                            AppBarActions(
                                listOf(
                                    // SY -->
                                    // 「暂停」：和右下角的 FAB 不同，FAB 是「下载器在跑」的
                                    // 一键切换，这里是**下载/上传分开**的显式操作 ——
                                    // 用户要能只停上传或者只停下载。见 pauseDialog 注释。
                                    AppBar.Action(
                                        title = stringResource(SYMR.strings.action_pause_all),
                                        icon = Icons.Outlined.Pause,
                                        onClick = { showPauseDialog = true },
                                    ),
                                    // SY <--
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.AutoMirrored.Outlined.Sort,
                                        onClick = { sortExpanded = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { screenModel.clearQueue() },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                val isRunning by screenModel.isDownloaderRunning.collectAsState()
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (isRunning) {
                            MR.strings.action_pause
                        } else {
                            MR.strings.action_resume
                        }
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (isRunning) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        }
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        // SY -->
                        // 需求：这里的「继续」要能分开选 —— 下载 / 上传 / 两个都要。
                        // 所以不再是直接 startDownloads()，而是弹一个三选（外加取消）。
                        // （「暂停」在顶栏里，见 showPauseDialog 那一段。）
                        if (isRunning) {
                            showPauseDialog = true
                        } else {
                            showResumeDialog = true
                        }
                        // SY <--
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = downloadList.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (downloadList.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val left = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx().roundToInt() }
            val top = with(density) { contentPadding.calculateTopPadding().toPx().roundToInt() }
            val right = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx().roundToInt() }
            val bottom = with(density) { contentPadding.calculateBottomPadding().toPx().roundToInt() }

            Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        screenModel.controllerBinding = DownloadListBinding.inflate(LayoutInflater.from(context))
                        screenModel.adapter = DownloadAdapter(screenModel.listener)
                        screenModel.controllerBinding.root.adapter = screenModel.adapter
                        screenModel.adapter?.isHandleDragEnabled = true
                        screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                        ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                        scope.launchUI {
                            screenModel.getDownloadStatusFlow()
                                .collect(screenModel::onStatusChange)
                        }
                        scope.launchUI {
                            screenModel.getDownloadProgressFlow()
                                .collect(screenModel::onUpdateDownloadedPages)
                        }

                        screenModel.controllerBinding.root
                    },
                    update = {
                        screenModel.controllerBinding.root
                            .updatePadding(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                            )

                        screenModel.adapter?.updateDataSet(downloadList)
                    },
                )
            }
        }

        // SY -->
        // 「继续」：下载 / 上传 / 下载和上传，三选（第四项是取消）。
        if (showResumeDialog) {
            DownloadUploadActionDialog(
                title = stringResource(SYMR.strings.download_resume_dialog_title),
                onDismissRequest = { showResumeDialog = false },
                onDownload = { screenModel.resumeDownloadsOnly() },
                onUpload = { screenModel.resumeUploadsOnly() },
                onBoth = { screenModel.resumeDownloadsAndUploads() },
            )
        }

        // 「暂停」：同一套三选。上传侧和下载侧是独立开关，
        // 所以「暂停上传」不会影响正在跑的下载（反过来也一样）。
        if (showPauseDialog) {
            DownloadUploadActionDialog(
                title = stringResource(SYMR.strings.download_pause_dialog_title),
                onDismissRequest = { showPauseDialog = false },
                onDownload = { screenModel.pauseDownloadsOnly() },
                onUpload = { screenModel.pauseUploadsOnly() },
                onBoth = { screenModel.pauseDownloadsAndUploads() },
            )
        }
        // SY <--
    }
}

// SY -->
/**
 * 「下载 / 上传 / 下载和上传」三选弹窗。
 *
 * 「继续」和「暂停」共用一个形状，只有标题和三个回调不同 ——
 * 两个方向的操作是对称的，没必要写两份几乎一样的对话框。
 * 不显示「取消」按钮：点弹窗外部就是取消，四个选项里再放一个取消会显得很挤。
 */
@Composable
private fun DownloadUploadActionDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onBoth: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column {
                DownloadUploadActionRow(
                    text = stringResource(SYMR.strings.action_download),
                    onClick = { onDismissRequest(); onDownload() },
                )
                DownloadUploadActionRow(
                    text = stringResource(SYMR.strings.action_upload),
                    onClick = { onDismissRequest(); onUpload() },
                )
                DownloadUploadActionRow(
                    text = stringResource(SYMR.strings.action_download_and_upload),
                    onClick = { onDismissRequest(); onBoth() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadUploadActionRow(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}
// SY <--
