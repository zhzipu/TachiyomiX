package eu.kanade.tachiyomi.ui.upload

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import eu.kanade.tachiyomi.databinding.UploadListBinding
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

/**
 * 上传队列页面。与 `DownloadQueueScreen` 是同一套壳子
 *（AppBar + Pill 计数 + 排序菜单 + 全部取消 + 可暂停的 FAB + AndroidView 包 RecyclerView），
 * 只是没有「按章节号排序」——上传的粒度是整本漫画。
 */
object UploadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UploadQueueScreenModel() }
        val uploadList by screenModel.state.collectAsState()
        val uploadCount by remember {
            derivedStateOf { uploadList.sumOf { it.subItems.size } }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // 这几行只是为了 FAB 的展开 / 收起状态
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
                                text = stringResource(SYMR.strings.label_upload_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (uploadCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$uploadCount",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        // SY -->
                        // 「下载」入口放在这里（用户要求）：从上传队列能直接跳到下载队列。
                        // 它和下面的排序 / 全部取消不同 —— 队列空的时候也要能用，
                        // 否则「上传队列是空的，但我想去看下载」就点不到了。
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(SYMR.strings.action_download_manager),
                                    icon = Icons.Outlined.Download,
                                    onClick = {
                                        navigator.push(DownloadQueueScreen)
                                    },
                                ),
                            ),
                        )
                        // SY <--
                        if (uploadList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.task.chapterNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.task.chapterNumber },
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
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = Icons.AutoMirrored.Outlined.Sort,
                                        onClick = { sortExpanded = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(SYMR.strings.action_cancel_all_uploads),
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
                val isRunning by screenModel.isUploaderRunning.collectAsState()
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
                        if (isRunning) {
                            screenModel.pauseUploads()
                        } else {
                            screenModel.startUploads()
                        }
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = uploadList.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (uploadList.isEmpty()) {
                EmptyScreen(
                    stringRes = SYMR.strings.information_no_uploads,
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
                        screenModel.controllerBinding = UploadListBinding.inflate(LayoutInflater.from(context))
                        screenModel.adapter = UploadQueueAdapter(screenModel.listener)
                        screenModel.controllerBinding.root.adapter = screenModel.adapter
                        screenModel.adapter?.isHandleDragEnabled = true
                        screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                        ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

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

                        screenModel.adapter?.updateDataSet(uploadList)
                    },
                )
            }
        }
    }
}
