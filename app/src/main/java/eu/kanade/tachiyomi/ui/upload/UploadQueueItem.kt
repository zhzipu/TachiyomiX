package eu.kanade.tachiyomi.ui.upload

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.upload.UploadTask

/**
 * 上传队列里的一行 = 一话待上传的章节。
 *
 * 上传已改成章节级任务，所以这里的粒度就是 `UploadTask`（一话）。
 * 身份用 `chapterId` —— 同一本漫画里它唯一，队列去重也按它走。
 */
class UploadQueueItem(
    val task: UploadTask,
    header: UploadQueueHeaderItem,
) : AbstractSectionableItem<UploadQueueHolder, UploadQueueHeaderItem>(header) {

    override fun getLayoutRes(): Int {
        return R.layout.upload_item
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): UploadQueueHolder {
        return UploadQueueHolder(view, adapter as UploadQueueAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: UploadQueueHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(header)
    }

    /** 拖动的对象是「一话」，所以整行都能拖。 */
    override fun isDraggable(): Boolean {
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is UploadQueueItem) {
            return task.chapterId == other.task.chapterId
        }
        return false
    }

    override fun hashCode(): Int {
        return task.chapterId.hashCode()
    }
}
