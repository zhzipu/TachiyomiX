package eu.kanade.presentation.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * SY -->
 * 「下载」分类里的删除确认（[eu.kanade.tachiyomi.ui.library.LibraryScreenModel.Dialog.DeleteManga]
 * 的 `downloadsOnly` 版）。
 *
 * 与 [DeleteLibraryMangaDialog] 的区别就是**没有勾选项**：那个对话框问的是
 * 「要不要一并从书架删掉 / 要不要一并删掉已下载章节」，因为在书架里删除本来就有几种意图；
 * 而「下载」分类里要干什么是确定的 —— 删本地下载、把这本漫画移出「下载」分类，
 * 书架归属一概不动。所以只需要二次确认一次。
 *
 * @param onDismissRequest 取消（含点外部 / 返回键），关掉对话框
 * @param onConfirm 确认删除。调用方负责关闭对话框。
 */
@Composable
fun DeleteDownloadsDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(SYMR.strings.download_category_delete_confirm))
            }
        },
        title = { Text(text = stringResource(SYMR.strings.download_category_delete_title)) },
        text = { Text(text = stringResource(SYMR.strings.download_category_delete_message)) },
    )
}
