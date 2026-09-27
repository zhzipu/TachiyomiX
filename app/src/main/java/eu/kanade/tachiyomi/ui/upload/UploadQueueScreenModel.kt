package eu.kanade.tachiyomi.ui.upload

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.upload.UploadManager
import eu.kanade.tachiyomi.data.upload.UploadStatus
import eu.kanade.tachiyomi.data.upload.UploadTask
import eu.kanade.tachiyomi.databinding.UploadListBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 上传队列页面（「更多」→ 上传队列）。
 *
 * 结构上照抄 `DownloadQueueScreenModel`（列表状态 + adapter + listener），
 * 差别在于上传的粒度是**一本漫画一个任务**，所以
 * - 分组头的 `size` 没有章节数可填，改成标题右边不显示计数（副标题统一在下面那行）；
 * - 每本漫画对应一个 header + 一条 item，界面观感与下载队列一致。
 *
 * 状态来源有两份：`queueState`（排队的）+ `currentTask`（正在传的那一本），
 * 两者拼起来才是用户眼里的「上传队列」。进度来自 `state`（键是 manga id）。
 */
class UploadQueueScreenModel(
    private val uploadManager: UploadManager = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<UploadQueueHeaderItem>())
    val state = _state.asStateFlow()

    lateinit var controllerBinding: UploadListBinding

    var adapter: UploadQueueAdapter? = null

    val listener = object : UploadQueueAdapter.UploadItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            reorder(flattenTasks(adapter))
        }

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) as? UploadQueueItem ?: return
            when (menuItem.itemId) {
                R.id.move_to_top, R.id.move_to_bottom -> {
                    val headerItems = adapter?.headerItems ?: return
                    val newTasks = mutableListOf<UploadTask>()
                    headerItems.forEach { headerItem ->
                        headerItem as UploadQueueHeaderItem
                        if (headerItem == item.header) {
                            headerItem.removeSubItem(item)
                            if (menuItem.itemId == R.id.move_to_top) {
                                headerItem.addSubItem(0, item)
                            } else {
                                headerItem.addSubItem(item)
                            }
                        }
                        newTasks.addAll(headerItem.subItems.map { it.task })
                    }
                    reorder(newTasks)
                }
                R.id.cancel_upload -> {
                    cancel(listOf(item.task))
                }
            }
        }

        /** 按界面当前顺序摊平成任务列表。 */
        private fun flattenTasks(adapter: UploadQueueAdapter): List<UploadTask> =
            adapter.headerItems.flatMap { header ->
                adapter.getSectionItems(header).map { (it as UploadQueueItem).task }
            }
    }

    init {
        screenModelScope.launch {
            combine(
                uploadManager.queueState,
                uploadManager.state,
                uploadManager.currentTask,
            ) { queue, states, current ->
                // 正在传的那一话排在最前（它就是队首），后面跟着排队的。
                val ordered = buildList<UploadTask> {
                    current?.let { add(it) }
                    addAll(queue.filterNot { it.chapterId == current?.chapterId })
                }
                ordered.mapNotNull { task ->
                    val uploadState = states[task.mangaId]
                    val chapterState = uploadState?.chapters?.get(task.chapterId)
                    // 已经不在队列里、状态又是终态（完成 / 取消 / 失败）的条目不再展示 ——
                    // 否则「取消」之后那一行还会留在列表上，看着像没取消掉。
                    if (task.chapterId != current?.chapterId && chapterState?.status.isTerminal()) {
                        return@mapNotNull null
                    }
                    UploadQueueHeaderItem(
                        mangaId = task.mangaId,
                        chapterId = task.chapterId,
                        mangaName = uploadState?.mangaTitle.orEmpty(),
                        chapterName = task.chapterName,
                        chapterState = chapterState,
                    ).apply {
                        addSubItem(UploadQueueItem(task, this))
                    }
                }
            }.collect { newList -> _state.update { newList } }
        }
    }

    override fun onDispose() {
        adapter = null
    }

    val isUploaderRunning = uploadManager.isUploaderRunning
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun startUploads() {
        uploadManager.startUploads()
    }

    fun pauseUploads() {
        uploadManager.pauseUploads()
    }

    fun clearQueue() {
        uploadManager.clearQueue()
    }

    fun reorder(tasks: List<UploadTask>) {
        uploadManager.reorderQueue(tasks)
    }

    fun cancel(tasks: List<UploadTask>) {
        uploadManager.cancelQueuedUploads(tasks)
    }

    /** 排序。上传的粒度是一话，所以能排的就是章节号（和下载队列同一种口径）。 */
    fun <R : Comparable<R>> reorderQueue(selector: (UploadQueueItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newTasks = mutableListOf<UploadTask>()
        adapter.headerItems.forEach { headerItem ->
            headerItem as UploadQueueHeaderItem
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) {
                    reverse()
                }
            }
            newTasks.addAll(headerItem.subItems.map { it.task })
        }
        reorder(newTasks)
    }

    private fun UploadStatus?.isTerminal(): Boolean =
        this == UploadStatus.COMPLETED ||
            this == UploadStatus.ERROR ||
            this == UploadStatus.CANCELLED
}
