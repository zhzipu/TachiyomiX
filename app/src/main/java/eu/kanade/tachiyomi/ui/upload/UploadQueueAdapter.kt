package eu.kanade.tachiyomi.ui.upload

import android.view.MenuItem
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem

/**
 * Adapter 里存的是上传队列的列表（一话一行）。
 *
 * 与下载侧 `DownloadAdapter` 是同一套写法（FlexibleAdapter + 可拖动）。
 * 每话自成一个分组头（分组头显示漫画名）、下面挂这一话那一行，
 * 所以界面上是「漫画名 + 章节名」两层 —— 和下载队列的观感一致。
 *
 * @param uploadItemListener 菜单点击 / 拖动结束的回调。
 */
class UploadQueueAdapter(val uploadItemListener: UploadItemListener) : FlexibleAdapter<AbstractFlexibleItem<*>>(
    null,
    uploadItemListener,
    true,
) {

    override fun shouldMove(fromPosition: Int, toPosition: Int): Boolean {
        // 上传是全局一条队列（一话一行），跨漫画也能拖 —— 和下载不同：
        // 下载有「同一图源才是一组」的约束，上传没有分组语义，用户想怎么排就怎么排。
        return true
    }

    interface UploadItemListener {
        fun onItemReleased(position: Int)
        fun onMenuItemClick(position: Int, menuItem: MenuItem)
    }
}
