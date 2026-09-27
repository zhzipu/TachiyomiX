package eu.kanade.tachiyomi.ui.upload

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractExpandableHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.upload.UploadChapterState

/**
 * 上传队列里的**一话**（每话自成一个分组，所以它同时是分组头）。
 *
 * 上传已改成章节级任务，队列页每行就是一话 —— 这样用户能按话取消、按话重排。
 * 分组头显示「漫画名」，下面那一行显示章节名 + 这一话的进度。
 *
 * @param mangaId 所属漫画 id
 * @param chapterId 这一话的 id（唯一键）
 * @param mangaName 漫画标题
 * @param chapterName 章节名
 * @param chapterState 这一话的状态（进度 / 失败原因）
 */
data class UploadQueueHeaderItem(
    val mangaId: Long,
    val chapterId: Long,
    val mangaName: String,
    val chapterName: String,
    val chapterState: UploadChapterState?,
) : AbstractExpandableHeaderItem<UploadQueueHeaderHolder, UploadQueueItem>() {

    override fun getLayoutRes(): Int {
        return R.layout.upload_header
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): UploadQueueHeaderHolder {
        return UploadQueueHeaderHolder(view, adapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: UploadQueueHeaderHolder,
        position: Int,
        payloads: List<Any?>?,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UploadQueueHeaderItem

        if (chapterId != other.chapterId) return false
        if (chapterState != other.chapterState) return false
        if (subItemsCount != other.subItemsCount) return false
        if (subItems !== other.subItems) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mangaId.hashCode()
        result = 31 * result + chapterId.hashCode()
        result = 31 * result + (chapterState?.hashCode() ?: 0)
        return result
    }

    init {
        isHidden = false
        isExpanded = true
        isSelectable = false
    }
}
