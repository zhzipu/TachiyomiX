package eu.kanade.tachiyomi.ui.upload

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.upload.UploadStatus
import eu.kanade.tachiyomi.databinding.UploadItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.sy.SYMR

/**
 * 上传队列里那一行「一话」的 ViewHolder。
 *
 * 上传是章节级任务，所以一行就是一话：上行显示章节名，下行显示状态，
 * 进度条的分母是**这一话**的总页数（在入队时就算好了，不会出现中途变大变小）。
 */
class UploadQueueHolder(private val view: View, val adapter: UploadQueueAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = UploadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    private lateinit var item: UploadQueueHeaderItem

    fun bind(header: UploadQueueHeaderItem) {
        this.item = header

        binding.mangaFullTitle.text = header.chapterName

        val context = view.context
        val state = header.chapterState
        val status = state?.status
        binding.uploadStatus.text = when (status) {
            UploadStatus.UPLOADING -> context.stringResource(SYMR.strings.upload_status_uploading)
            UploadStatus.WAITING_CONFIRM -> context.stringResource(SYMR.strings.upload_status_waiting_confirm)
            UploadStatus.QUEUED, null -> context.stringResource(SYMR.strings.upload_status_queued)
            UploadStatus.COMPLETED -> context.stringResource(SYMR.strings.upload_status_completed)
            UploadStatus.ERROR -> state.message
                ?: context.stringResource(SYMR.strings.upload_status_error)
            UploadStatus.CANCELLED -> context.stringResource(SYMR.strings.upload_status_cancelled)
        }

        val pagesTotal = state?.pagesTotal ?: 0
        if (pagesTotal > 0) {
            binding.uploadProgress.isIndeterminate = false
            binding.uploadProgress.max = 100
            binding.uploadProgress.setProgressCompat(state?.progress ?: 0, false)
            binding.uploadProgressText.text = "${state?.pagesUploaded ?: 0}/$pagesTotal"
        } else {
            binding.uploadProgress.isIndeterminate = false
            binding.uploadProgress.max = 100
            binding.uploadProgress.setProgressCompat(0, false)
            binding.uploadProgressText.text = ""
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.uploadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        view.popupMenu(
            menuRes = R.menu.upload_single,
            initMenu = {
                // 位置判断和下载侧同一套规矩（第 0 位是分组头）
                findItem(R.id.move_to_top).isVisible = bindingAdapterPosition > 1
                findItem(R.id.move_to_bottom).isVisible =
                    bindingAdapterPosition != adapter.itemCount - 1
            },
            onMenuItemClick = {
                adapter.uploadItemListener.onMenuItemClick(bindingAdapterPosition, this)
            },
        )
    }
}
