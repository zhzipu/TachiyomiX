package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.upload.LibraryMangaProgress
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 书架「下载」分类下某个项目要画的进度条，已经算好了「该画哪一条」。
 *
 * **下载组**两条（**同色 `primary`**）：
 * - 页数条 —— 已下载页数 / 该话总页数，看「当前正在下的那一话」
 * - 章节条 —— 已下载章节数 /（已下载 + 队列中未下载）章节数，看「整批下载任务」
 *
 * **上传组**两条（**同色 `tertiary`，失败时 `error`**）：
 * - 页数条 —— 已上传页数 / 该话总页数，看「当前正在传的那一话」
 * - 章节条 —— 已上传章节数 / 需上传章节总数，看「整批上传任务」
 *
 * **两组用不同颜色区分归属**（组内同色，所以组内两条是靠「哪两条、粗细位置」而不是颜色区分）：
 * 下载组 `primary`、上传组 `tertiary`，上传失败时该组两条一起转 `error`。
 *
 * 组内一律「页数条 → 章节条」：页数条讲「当前这一话跑到哪了」，
 * 章节条讲「这批任务整体完成了几分之几」，两件事。
 * 下载组与上传组**可以同时出现**：上传是「一话下完就传」，与下载并行，
 * 不存在「先下完再传」的先后关系，所以不能靠互斥躲开重叠，改用颜色区分归属。
 * 章节条各自**只涉及 1 话时不画**（分母 1 → 只有空/满两个状态，没有信息量）。
 *
 * 整体顺序见 [LibraryItemProgressBars]。
 *
 * 进度条不带任何文字，只靠颜色区分；文字只保留在 `contentDescription` 里供读屏使用。
 *
 * @property downloading 有下载任务（排队中或下载中）
 * @property pagesDone 当前这一章已下载的页数
 * @property pagesTotal 当前这一章的总页数（页表还没取到时为 0）
 * @property uploadActive 有上传任务（排队 / 等确认 / 上传中）
 * @property uploadPagesDone 当前正在上传那一话已上传的页数
 * @property uploadPagesTotal 当前正在上传那一话的总页数（还没数出来时为 0）
 * @property uploadFailed 上传失败
 * @property chaptersDone 本地已下载完成的章节数
 * @property chaptersPending 在下载队列里、还没下完的章节数（**不在队列里的章节不算**）
 * @property uploadChaptersDone 本批次已上传完成的章节数
 * @property uploadChaptersTotal 本批次需要上传的章节总数
 */
data class LibraryItemProgress(
    val downloading: Boolean,
    val pagesDone: Int,
    val pagesTotal: Int,
    val uploadActive: Boolean,
    val uploadPagesDone: Int,
    val uploadPagesTotal: Int,
    val uploadFailed: Boolean,
    // SY -->
    val chaptersDone: Int = 0,
    val chaptersPending: Int = 0,
    val uploadChaptersDone: Int = 0,
    val uploadChaptersTotal: Int = 0,
    // SY <--
) {
    /**
     * 下载那一条。
     *
     * 只在**真的有下载任务**时显示 —— 没有任务时那个比例是个静态数字，
     * 没必要一直占着位置；任务完成后（100%）同样属于「没有任务」。
     */
    val showDownloadBar: Boolean get() = downloading

    /**
     * 上传那一条。
     *
     * 规则：
     * - **上传进行中（排队 / 等确认 / 正在传）就显示**，和下载条**可以同时出现** ——
     *   上传已经改成「一话下完就传」，与下载并行，所以不能再靠「下载时不显示上传条」
     *   来躲开重叠；两组并存时用颜色区分（下载组 `primary`，上传组 `tertiary`）。
     * - **上传结束就不显示**（这是「上传完毕后隐藏上传进度条」）；唯一例外是**失败**，
     *   那是有事要用户处理的结果。
     */
    val showUploadBar: Boolean get() = uploadActive || uploadFailed

    /**
     * 下载章节条 —— **下载组的第二条**，紧接在下载页数条下面。
     *
     * **本次任务只涉及 1 章时不画**：这时分母是 1，条子只可能是「空」或「满」两个状态，
     * 中间没有过渡，画出来没有信息量（用户明确要求）。
     *
     * 何谓「本次任务」：分母 = [chaptersDone]（本地已下载章数）+ [chaptersPending]
     * （下载队列里还没下完的章数），**不在队列里的章节不算**。所以
     * 「下了 5 章」时它显示 20%、40%…；而「只下了 1 章」时整条不画。
     *
     * 与 [showDownloadBar] / [showUploadBar] **不互斥**：页数条讲单章，
     * 章节条讲本次任务的整体进度，两件事可以同屏。
     */
    val showChapterBar: Boolean get() = chapterTotal > 1

    /** 一条都不用画时整个组件不产出任何布局节点（不留空档）。 */
    val visible: Boolean get() = showDownloadBar || showUploadBar || showChapterBar || showUploadChapterBar

    /** 「已下载页数 / 总页数」换算成 0..100；页表还没取到时算 0。 */
    val downloadProgress: Int
        get() = if (pagesTotal <= 0) 0 else (pagesDone * 100 / pagesTotal).coerceIn(0, 100)

    /** 「已上传页数 / 总上传页数」换算成 0..100；页数还没数出来时算 0。 */
    val uploadProgress: Int
        get() = if (uploadPagesTotal <= 0) 0 else (uploadPagesDone * 100 / uploadPagesTotal).coerceIn(0, 100)

    /**
     * 上传章节条 —— **上传组的第二条**，紧接在上传页数条下面。
     *
     * 与 [showChapterBar] 同一套规则：
     * - **本批次只上传 1 话时不画**（分母 1 → 只有空/满两态，没有信息量，用户明确要求）。
     * - **上传跑完就不画**：整组上传条是一起显隐的，否则会留一条 100% 的条在那里
     *   （`UploadState` 完成后仍留在 state 里）。失败是例外，仍要显示给用户看。
     */
    val showUploadChapterBar: Boolean
        get() = (uploadActive || uploadFailed) && uploadChaptersTotal > 1

    /** 「已上传章节数 / 需上传章节总数」换算成 0..100；总数为 0 时算 0。 */
    val uploadChapterProgress: Int
        get() = if (uploadChaptersTotal <= 0) 0 else (uploadChaptersDone * 100 / uploadChaptersTotal).coerceIn(0, 100)

    /**
     * 「已下载章节数 / 本次要下载的章节数」换算成 0..100。
     *
     * 分母 = 已下载 + **队列中**未下载；不在队列里的章节不参与，见
     * [LibraryMangaProgress.chaptersPending]。
     */
    val chapterProgress: Int
        get() = if (chapterTotal <= 0) 0 else (chaptersDone * 100 / chapterTotal).coerceIn(0, 100)

    /** 章节口径的总章节数（进度条分母）。 */
    val chapterTotal: Int get() = chaptersDone + chaptersPending

    companion object {
        fun of(progress: LibraryMangaProgress): LibraryItemProgress = LibraryItemProgress(
            downloading = progress.downloading,
            pagesDone = progress.pagesDone,
            pagesTotal = progress.pagesTotal,
            uploadActive = progress.uploadActive,
            uploadPagesDone = progress.uploadPagesDone,
            uploadPagesTotal = progress.uploadPagesTotal,
            uploadFailed = progress.uploadFailed,
            // SY -->
            chaptersDone = progress.chaptersDone,
            chaptersPending = progress.chaptersPending,
            uploadChaptersDone = progress.uploadChaptersDone,
            uploadChaptersTotal = progress.uploadChaptersTotal,
            // SY <--
        )
    }
}

/**
 * 书架项目下方的进度条。
 *
 * **按需显隐**，规则见 [LibraryItemProgress.showDownloadBar]、
 * [LibraryItemProgress.showUploadBar]、[LibraryItemProgress.showChapterBar]
 * 与 [LibraryItemProgress.showUploadChapterBar]；一条都不该画时直接不产出布局。
 *
 * 顺序（自上而下）：**下载组**（下载页数条 → 下载章节条）
 * → **上传组**（上传页数条 → 上传章节条）。
 *
 * 四条的显隐各自独立：上传与下载已经并行（「一话下完就传」），
 * 所以两组都可能同屏，靠颜色区分归属。
 *
 * 文字标签去掉了，所以给每条补一个 `contentDescription` 供读屏使用
 * （不影响视觉，也不占高度）。
 */
@Composable
fun LibraryItemProgressBars(
    progress: LibraryItemProgress,
    modifier: Modifier = Modifier,
) {
    if (!progress.visible) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (progress.showDownloadBar) {
            ProgressBar(
                value = progress.downloadProgress,
                color = MaterialTheme.colorScheme.primary,
                description = stringResource(
                    SYMR.strings.library_progress_pages,
                    progress.pagesDone,
                    progress.pagesTotal,
                ),
            )
        }

        // SY -->
        // 顺序：**下载组**（页数条 → 章节条）→ **上传组**（页数条 → 章节条）。
        // 组内同色：下载组两条都用 `primary`，上传组两条都用 `tertiary`（失败 `error`），
        // 靠「分组色」区分归属，而不是同组内再分色。
        if (progress.showChapterBar) {
            ProgressBar(
                value = progress.chapterProgress,
                color = MaterialTheme.colorScheme.primary,
                description = stringResource(
                    SYMR.strings.library_progress_chapters,
                    progress.chaptersDone,
                    progress.chapterTotal,
                ),
            )
        }

        // 上传页数条：上传组色 `tertiary`，失败时转 `error`。
        if (progress.showUploadBar) {
            ProgressBar(
                value = progress.uploadProgress,
                color = if (progress.uploadFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                description = stringResource(
                    SYMR.strings.library_progress_upload_pages,
                    progress.uploadPagesDone,
                    progress.uploadPagesTotal,
                ),
            )
        }

        // 上传章节条：与上传页数条同色（`tertiary`），失败时同样转 `error`。
        if (progress.showUploadChapterBar) {
            ProgressBar(
                value = progress.uploadChapterProgress,
                color = if (progress.uploadFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                description = stringResource(
                    SYMR.strings.library_progress_upload_chapters,
                    progress.uploadChaptersDone,
                    progress.uploadChaptersTotal,
                ),
            )
        }
        // SY <--
    }
}

@Composable
private fun ProgressBar(
    value: Int,
    color: Color,
    description: String,
) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0, 100) / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics { contentDescription = description },
        color = color,
    )
}
