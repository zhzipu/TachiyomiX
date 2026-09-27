package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: ReaderItem? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersDoubleShift(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            // SY -->
            if (pager.isRestoring) return
            // SY <--
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        // 增强开启时，让离屏页数与增强预加载页数一致：相邻页保留在 ViewPager 中就不会被
        // 回收重建，翻页时不必重新加载，避免出现长时间黑屏
        val readerPreferences = Injekt.get<ReaderPreferences>()
        pager.offscreenPageLimit = if (ReaderEnhancement.isEnabled(readerPreferences)) {
            // 需求 4：预加载原图最少 3 页，保证增强有足够已加载原图可增量处理
            readerPreferences.realCuganPreloadSize().get().coerceAtLeast(3)
        } else {
            2
        }
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
                NavigationRegion.NONE -> {}
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.joinedItems.getOrNull(pager.currentItem)
                val firstPage = item?.first as? ReaderPage
                val secondPage = item?.second as? ReaderPage
                if (firstPage is ReaderPage) {
                    activity.onPageLongTap(firstPage, secondPage)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.reloadChapterListener = {
            activity.reloadChapters(it)
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance<PagerPageHolder>()
            .firstOrNull { it.item.first == page || it.item.second == page }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    fun onPageChange(position: Int) {
        val pagePair = adapter.joinedItems.getOrNull(position)
        val page = pagePair?.first
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                currentPage is ReaderPage && page is ReaderPage -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == (currentPage as ReaderPage).number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > (currentPage as ReaderPage).number
                    }
                }
                currentPage is ChapterTransition.Prev && page is ReaderPage ->
                    false
                else -> true
            }
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward, pagePair.second != null)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean, hasExtraPage: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, hasExtraPage)

        // 跨过渡页从上一章进入本章首页时，viewerChapters.currChapter 由 loadNewChapter 异步更新、
        // 此刻仍指向旧章；且进入过渡页时已 cancelAll。这里同步把增强状态重置到本章正确页，
        // 让 getPageHolder.onPageSelected → requestEnhancement 能以新章正确目标页启动增强，
        // 否则第一页的增强永远不会自动开启。
        val currentChapter = activity.viewModel.state.value.viewerChapters?.currChapter
        if (page.chapter != currentChapter) {
            logcat { "onReaderPageSelected: cross-chapter to ${page.chapter.chapter.url}, reset enhancer to page ${page.index}" }
            ImageEnhancer.reset(page.index)
        }

        // Notify holder of page change
        getPageHolder(page)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        // 过渡页不显示页码（上一章/下一章切换页）
        activity.onPageSelected(transition)
        // 需求：进入过渡页时停止增强队列（当前章已读完，不再预加载增强任务）
        ImageEnhancer.cancelAll(reason = "entered chapter transition")
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersDoubleShift(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition =
            config.alwaysShowChapterTransition ||
                adapter.joinedItems.getOrNull(pager.currentItem)?.first is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        onPageChange(pager.currentItem)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.joinedItems.indexOfFirst { it.first == page || it.second == page }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            } else {
                // Call this since with double shift onPageChange wont get called (it shouldn't)
                // Instead just update the page count in ui
                val joinedItem = adapter.joinedItems.firstOrNull { it.first == page || it.second == page }
                activity.onPageSelected(
                    joinedItem?.first as? ReaderPage ?: page,
                    joinedItem?.second != null,
                )
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }

    // SY -->
    fun setChaptersDoubleShift(chapters: ViewerChapters) {
        setChaptersInternal(chapters)
        // 单/双页切换或双页错位后，当前 spread 的「首页」可能没变（单页变双页、双页变单页），
        // onPageChange 因此不会回调，这里主动刷新一次，保证页码显示跟着变
        notifySpreadChanged()
    }

    /**
     * 按当前 spread 主动通知 activity 刷新页码显示。
     */
    private fun notifySpreadChanged() {
        val joinedItem = adapter.joinedItems.getOrNull(pager.currentItem) ?: return
        val first = joinedItem.first as? ReaderPage ?: return
        activity.onPageSelected(first, joinedItem.second != null)
    }

    /**
     * 当前 spread 的两个页：[first] 为 pair 里的第一页，[second] 为 null 表示单页显示。
     */
    fun currentSpread(): Pair<ReaderPage, ReaderPage?>? {
        val joinedItem = adapter.joinedItems.getOrNull(pager.currentItem) ?: return null
        val first = joinedItem.first as? ReaderPage ?: return null
        return first to (joinedItem.second as? ReaderPage)
    }

    /**
     * 原位重合并当前双页并刷新页码（请求 1+黑屏修复）：
     * 用 holder 的「插底层」路径重合并，不清空视图、不重建 adapter，避免 2 秒黑屏。
     */
    fun invertCurrentSpread() {
        val spread = currentSpread() ?: return
        if (spread.second == null) return // 单页显示无需处理
        getPageHolder(spread.first)?.refreshInverted()
        // 双页跨页：刷新页码显示（换位后数字也跟随）
        activity.onPageSelected(spread.first, true)
    }

    /**
     * 双页换位动画（请求 2）：长按反转双页顺序时，用两张源页做"左页向右、右页向左"的对滑
     * （下一次 [PagerPageHolder.mergePages] 就地触发）。[oldPageOnRight] 是换位前第 1 页是否显示在右侧。
     */
    fun animateDoublePageSwap(oldPageOnRight: Boolean) {
        val spread = currentSpread() ?: return
        if (spread.second == null) return // 单页显示无需换位动画
        getPageHolder(spread.first)?.requestSwapAnimation(oldPageOnRight)
    }

    fun updateShifting(page: ReaderPage? = null) {
        adapter.pageToShift = page ?: adapter.joinedItems.getOrNull(pager.currentItem)?.first as? ReaderPage
    }

    fun splitDoublePages(currentPage: ReaderPage) {
        adapter.splitDoublePages(currentPage)
    }

    fun getShiftedPage(): ReaderPage? = adapter.pageToShift
    // SY <--
}
