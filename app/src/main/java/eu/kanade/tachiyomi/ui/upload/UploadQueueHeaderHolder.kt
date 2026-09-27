package eu.kanade.tachiyomi.ui.upload

import android.annotation.SuppressLint
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.viewholders.ExpandableViewHolder
import eu.kanade.tachiyomi.databinding.UploadHeaderBinding

class UploadQueueHeaderHolder(view: View, adapter: FlexibleAdapter<*>) : ExpandableViewHolder(view, adapter) {

    private val binding = UploadHeaderBinding.bind(view)

    @SuppressLint("SetTextI18n")
    fun bind(item: UploadQueueHeaderItem) {
        setDragHandleView(binding.reorder)
        // 分组头是漫画名；章节名和进度画在下面那一行（`UploadQueueHolder`）。
        binding.title.text = item.mangaName
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
            mAdapter.collapseAll()
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        binding.container.isDragged = false
        mAdapter.expandAll()
        (mAdapter as UploadQueueAdapter).uploadItemListener.onItemReleased(position)
    }
}
