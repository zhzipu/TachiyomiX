package eu.kanade.presentation.library.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.data.upload.UploadPendingDecision
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 手动上传时，服务器上已经存在同名漫画的冲突弹窗。
 *
 * 三个出口对应 [eu.kanade.tachiyomi.data.upload.UploadChoice] 的三个值：
 * 合并 / 新建文件夹 / 取消（点外部或返回键都算取消）。
 *
 * 挂起等待选择的逻辑在 `UploadManager.askConflict` 里；这里只负责显示和把结果交回去。
 */
@Composable
fun UploadConflictDialog(
    decision: UploadPendingDecision,
    onMerge: () -> Unit,
    onNewFolder: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(SYMR.strings.upload_conflict_title))
        },
        text = {
            Text(
                text = stringResource(
                    SYMR.strings.upload_conflict_message,
                    decision.mangaTitle,
                    decision.remoteFolderName,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onMerge) {
                Text(text = stringResource(SYMR.strings.upload_conflict_merge))
            }
        },
        dismissButton = {
            TextButton(onClick = onNewFolder) {
                Text(text = stringResource(SYMR.strings.upload_conflict_new_folder))
            }
        },
        // 上传正在挂起等这个答案，误触外部就取消掉整次上传，所以关掉点击外部关闭
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
