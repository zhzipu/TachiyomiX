package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.ReaderEnhancement
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.max

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * 双击缩放是否被禁止，直接读取配置，所以改设置后立刻生效（不需要重建页面）。
     */
    override val disableDoubleTapZoom: Boolean
        get() = viewer.config.disableDoubleTapZoom

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    /**
     * Job waiting for the current page's image enhancement to finish.
     */
    private var enhancementRefreshJob: Job? = null

    /** 翻页后等待页面停留的延迟任务：期间再次翻页则取消，避免反复打断增强。 */
    private var enhancementSettleJob: Job? = null

    /**
     * 当前画面上是否已经是增强成品。用来避免重复 setImage：重新设置会让 SSIV 先清空画面，
     * 表现为翻页后闪一下。
     */
    private var showingEnhancedImage = false

    /** 本页增强完成回调在 [ImageEnhancer] 里的登记 key；null 表示尚未登记。 */
    private var enhancementPageKey: String? = null

    init {
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }
        // 增强总开关变化时平滑重载当前页（开启改用成品、关闭回到原图），避免整页闪黑
        scope.launch {
            Injekt.get<ReaderPreferences>().realCuganEnabled().changes().drop(1).collect {
                if (isVisibleOnScreen() && page.status == Page.State.Ready) {
                    setImage()
                }
            }
        }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        enhancementRefreshJob?.cancel()
        enhancementRefreshJob = null
        // 注销增强完成回调，避免残留（视图已分离，不应再被刷新）
        enhancementPageKey?.let(ImageEnhancer::removeOnEnhancedListener)
        enhancementPageKey = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    // SY -->
    /** 双页时加载圈该待的侧：true = 左半，false = 右半，null = 居中（单页或两页都齐）。 */
    private var progressIndicatorSide: Boolean? = null

    /** 双页里「还没出画面」的那一页所在侧；单页或两页都齐时为 null。 */
    private fun missingHalfSide(): Boolean? {
        val extra = extraPage ?: return null
        val mainReady = page.status == Page.State.Ready && page.stream != null
        val extraReady = extra.status == Page.State.Ready && extra.stream != null
        return when {
            mainReady && !extraReady -> false
            extraReady && !mainReady -> true
            else -> null
        }
    }

    /** 把加载圈挪到缺的那一侧（画布宽度的 1/4 处）；两页都齐则回到正中。 */
    private fun applyProgressIndicatorSide() {
        val indicator = progressIndicator ?: return
        indicator.translationX = when (progressIndicatorSide) {
            true -> -width / 4f
            false -> width / 4f
            null -> 0f
        }
    }

    /** 显示加载圈；双页只就绪一页时自动摆到缺的那一侧。 */
    private fun showProgressIndicator() {
        initProgressIndicator()
        progressIndicatorSide = missingHalfSide()
        progressIndicator?.show()
        applyProgressIndicatorSide()
    }

    /**
     * 双页凑齐（或单页）才收起加载圈；只就绪一页时把圈留在缺的那一侧，
     * 让已经加载好的那一页继续显示在它自己那一侧。
     */
    private fun hideProgressIndicatorIfSpreadComplete() {
        if (extraPage == null || missingHalfSide() == null) {
            progressIndicatorSide = null
            progressIndicator?.translationX = 0f
            progressIndicator?.hide()
        } else {
            showProgressIndicator()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyProgressIndicatorSide()
    }

    /**
     * 双页里只有一页就绪：把这一页按原尺寸画在**它自己那一侧**，另一侧用页面底色填满。
     *
     * 这样已加载好的那页立刻出现在该在的一侧（而不是被 FIT 到画面正中），另一侧由加载圈继续提示。
     */
    private fun mergePageWithBlank(imageSource: BufferedSource, onLeft: Boolean): BufferedSource {
        val bitmap = decodeImage(imageSource) ?: return handleWideImage(imageSource)
        // 本身就是跨页宽图：按整页显示
        if (bitmap.width > bitmap.height) return handleWideImage(imageSource)

        val background = viewer.config.pageCanvasColor
        val blank = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        blank.eraseColor(background)
        return if (onLeft) {
            ImageUtil.mergeBitmaps(bitmap, blank, true, 0, background) { updateProgress(it) }
        } else {
            ImageUtil.mergeBitmaps(blank, bitmap, true, 0, background) { updateProgress(it) }
        }
    }

    /**
     * 双页里「配对页先就绪、本页还没」时的渲染：把它画在自己那一侧，另一侧留页面底色，
     * 加载圈交给 [hideProgressIndicatorIfSpreadComplete] 摆到缺的那一侧。
     */
    private suspend fun renderSingleHalf(imageSource: BufferedSource, onLeft: Boolean) {
        val merged = mergePageWithBlank(imageSource, onLeft)
        val isAnimated = ImageUtil.isAnimatedAndSupported(merged)
        val background = if (!isAnimated && viewer.config.automaticBackground) {
            ImageUtil.chooseBackground(context, merged.peek())
        } else {
            null
        }
        withUIContext {
            setImage(
                merged,
                isAnimated,
                Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = viewer.config.imageScaleType,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = viewer.config.imageZoomType,
                    landscapeZoom = viewer.config.landscapeZoom,
                ),
            )
            if (!isAnimated) {
                pageBackground = background
            }
            removeErrorLayout()
            hideProgressIndicatorIfSpreadComplete()
        }
    }
    // SY <--

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        showProgressIndicator()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        showProgressIndicator()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        showProgressIndicator()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        // 登记「增强完成即刷新」回调，覆盖后台预载页：增强一完成就把该页换成增强图。
        if (ReaderEnhancement.isEnabled()) {
            ensureEnhancementRefreshRegistered()
        }
        // 已经有画面时改用「底层插入」换图：原图留在上层遮挡，新图加载完成后才替代它，
        // 这样切换增强开关、切换成品时都不会闪黑
        if (hasReadyImage) {
            setNextImageInsertAtBottom()
        }
        if (extraPage == null) {
            progressIndicator?.setProgress(0)
        } else {
            progressIndicator?.setProgress(95)
        }

        // 图像增强：已有原生放大结果时直接显示放大后的图片（未开启增强时行为与之前一致）
        val enhancedFile = ReaderEnhancement.cachedFile(context, page)
        // 关闭增强后 page.stream 可能仍指向旧的增强成品，这里回退到原图流，让画面回到原图
        if (enhancedFile == null && page.usingEnhancedStream) {
            page.enhancementStream?.let {
                page.stream = it
                page.usingEnhancedStream = false
            }
        }
        // 页面流本身可能已经是增强成品（加载时命中过缓存），此时即使再次查询未命中也要保留水印
        showingEnhancedImage = enhancedFile != null || page.usingEnhancedStream
        // 增强成品在图片左上角叠加水印；双页合并显示时两张图片各显示一个
        setSuperResolutionWatermark(
            visible = showingEnhancedImage,
            pages = if (extraPage != null && !viewer.config.dualPageSplit) 2 else 1,
        )
        // 双页：配对页可能比本页先就绪，这时先把它画在它自己那一侧（右半），
        // 本页就绪后再走正常合并，避免「一边已经加载好了却什么都不显示」。
        if (enhancedFile == null && page.stream == null) {
            val extraStreamFn = extraPage?.stream
            if (extraStreamFn != null) {
                renderSingleHalf(Buffer().readFrom(extraStreamFn().buffered(16)), onLeft = false)
            }
            return
        }
        val streamFn: () -> InputStream = if (enhancedFile != null) {
            { enhancedFile.inputStream() }
        } else {
            page.stream ?: return
        }
        val streamFn2 = extraPage?.stream

        try {
            val (source, isAnimated, background) = withIOContext {
                streamFn().buffered(16).use { source ->
                    // SY -->
                    if (extraPage != null) {
                        streamFn2?.invoke()
                            ?.buffered(16)
                    } else {
                        null
                    }.use { source2 ->
                        val itemSource = when {
                            viewer.config.dualPageSplit -> process(item.first, Buffer().readFrom(source))
                            // 单页：沿用原行为（含宽图居中留白）
                            extraPage == null -> mergePages(Buffer().readFrom(source), null)
                            // 两页都就绪：合并成一跨页
                            source2 != null -> mergePages(Buffer().readFrom(source), Buffer().readFrom(source2))
                            // 只就绪本页（pair.first）：画在左侧，右侧留页面底色 + 加载圈
                            else -> mergePageWithBlank(Buffer().readFrom(source), onLeft = true)
                        }
                        // SY <--
                        val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                        val background = if (!isAnimated && viewer.config.automaticBackground) {
                            ImageUtil.chooseBackground(context, itemSource.peek())
                        } else {
                            null
                        }
                        Triple(itemSource, isAnimated, background)
                    }
                }
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
                // 画面已经交出去了：双页两页都齐（或单页）时收起加载圈；
                // 只就绪一页时把圈留在缺的那一侧，别盖住已经出来的那一页。
                // 双页换位会重建 holder（pair 变了 → getItemPosition 返回 POSITION_NONE），
                // 新 holder 的 setImage 走的是「页面早已 Ready」这条捷径，不会有 100 进度回调兜底，
                // 于是加载圈会一直挂在屏幕上。这里兜住。
                hideProgressIndicatorIfSpreadComplete()
            }

            // 图像增强：还没有放大结果时触发高优先级处理，处理完成后刷新画面
            if (enhancedFile == null) {
                requestEnhancement()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) return imageSource
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        // 屏幕左右 = pair 顺序：page(= pair.first) 在左，extraPage(= pair.second) 在右。
        // 双页交换已在 PagerViewerAdapter.setJoinedItems 里改好 pair，所以这里恒为 true，
        // 不再需要阅读方向/invert 之类的换算（那套公式与画面容易不一致）。
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        imageSource.close()
        imageSource2.close()

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, true, centerMargin, viewer.config.pageCanvasColor) {
            updateProgress(it)
        }
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        return if (
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            ImageUtil.isWideImage(imageSource) &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        } else {
            imageSource
        }
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            ImageDecoder.newInstance(imageSource.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                // 双页两页都齐才收起；只就绪一页时留在缺的那一侧
                hideProgressIndicatorIfSpreadComplete()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        // 换位覆盖层别挡着错误提示，也别等一张永远等不到的新图
        viewer.onSpreadImageLoadFailed()
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    // ---- 双页左右换位 --------------------------------------------------------

    override fun onImageLoaded() {
        super.onImageLoaded()
        // 双页两页都齐才收起加载圈；只就绪一页时把它留在缺的那一侧
        hideProgressIndicatorIfSpreadComplete()
        // 新图真正上屏了：换位覆盖层可以撤掉、并播两页对滑了（提前撤会被还没换上的画面盖住）
        viewer.onSpreadImageLoaded(this)
    }

    /**
     * 图像增强：当前页变为可见页，按高优先级排队处理。
     */
    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        onEnhancementTargetChanged()
        requestEnhancement()
    }

    /**
     * 当前可见页变化时，通知增强器更新「当前目标页」窗口，让队列按距新目标最近优先。
     * 只重排优先级，绝不打断正在进行的增强任务（需求 1）。
     * 注意：只在真正的翻页（onPageSelected）时触发，避免后台预载页把目标页带偏。
     */
    private fun onEnhancementTargetChanged() {
        if (!ReaderEnhancement.isEnabled()) return
        if (!isEnhancementTargetActive(page)) return
        ImageEnhancer.updateFocus(
            page.index,
            page.enhancementKeySuffix,
            extraPage?.index,
            extraPage?.enhancementKeySuffix.orEmpty(),
        )
    }

    /**
     * 判断某页是否属于「应当执行增强」的章节。
     * 常规情况：章节 == 当前活跃章（viewerChapters.currChapter）。
     * 跨过渡页进入下一章首页时：currChapter 由 loadNewChapter 异步更新、此刻仍指向旧章，
     * 但只要该页已是当前聚焦页（viewer.currentPage），就视为已切入新章并放行，让第一页增强自动开启；
     * 其余非当前章页面（过渡页期间被预载进 ViewPager、但仍未聚焦的后台页）照旧拦截。
     */
    private fun isEnhancementTargetActive(page: ReaderPage): Boolean {
        val activeChapter = viewer.activity.viewModel.state.value.viewerChapters?.currChapter
        if (activeChapter != null && page.chapter == activeChapter) return true
        return (viewer.currentPage as? ReaderPage)?.chapter == page.chapter
    }

    /**
     * 确保本页已登记「增强完成即刷新」回调（只登记一次）。
     * 回调在主线程触发：增强产物落盘后立即把画面换成增强图（后台页也刷新），
     * 翻到该页时无需再等解码，水印与增强图同步立即可见。
     */
    private fun ensureEnhancementRefreshRegistered() {
        if (enhancementPageKey != null) return
        val mangaId = viewer.activity.viewModel.manga?.id ?: return
        val chapterId = page.chapter.chapter.id ?: return
        val key = "${mangaId}_${chapterId}_${page.index}_${page.enhancementKeySuffix}"
        enhancementPageKey = key
        ImageEnhancer.addOnEnhancedListener(key) {
            scope.launch { onEnhancementDone() }
        }
    }

    /** 增强完成回调：若状态就绪且产物已落盘，把画面替换为增强图（底部插入不闪黑）。 */
    private suspend fun onEnhancementDone() {
        if (page.status != Page.State.Ready || showingEnhancedImage) return
        if (ReaderEnhancement.cachedFile(context, page) == null) return
        setNextImageInsertAtBottom()
        setImage()
    }

    /**
     * 图像增强：把当前页以高优先级入队，并在处理完成后刷新一次画面（换成放大后的图片）。
     * 未开启增强时不做任何事。
     */
    private fun requestEnhancement() {
        if (!ReaderEnhancement.isEnabled()) {
            // 增强已关闭：本页可能还停留在关闭前预加载好的增强成品上，
            // 切回来时平滑回退到原图（否则会一直显示增强图和水印）
            if (showingEnhancedImage || page.usingEnhancedStream) {
                scope.launch { setImage() }
            }
            return
        }
        // 只处理当前活跃章（或跨章后已聚焦的新章）的页面：过渡黑页期间被预载进 ViewPager 的非当前
        // 章页面不应加入增强队列，但跨过渡页聚焦到下一章首页时需放行让其自动开启增强。
        if (!isEnhancementTargetActive(page)) return
        val mangaId = viewer.activity.viewModel.manga?.id ?: return
        val chapterId = page.chapter.chapter.id ?: return

        // 按「预加载页数」提前排队后续页的增强，翻页时直接命中成品
        ReaderEnhancement.requestPreload(context, page)

        // 画面已经是放大成品时既不用排队也不用刷新：
        // 再走一次 setImage 只会让 SSIV 清空重载，翻页时表现为闪一下。
        if (showingEnhancedImage) return

        // 增强成品已就绪但画面仍是原图（后台预载时增强尚未完成，holder 先加载了原图）：
        // 翻到这一页时直接换成增强图，不必再等停留与轮询的延迟，避免先显示原图再切换。
        if (page.status == Page.State.Ready && ReaderEnhancement.cachedFile(context, page) != null) {
            // 新图插到最底层加载，原图留在上层，加载完成后才移除，因此不会闪黑
            setNextImageInsertAtBottom()
            scope.launch { setImage() }
            return
        }

        // 翻页后先等 1 秒：若期间没有再次翻页，再把当前页（双页时含配对页）插队处理，
        // 避免快速连续翻页时反复打断正在进行的增强任务。
        enhancementSettleJob?.cancel()
        enhancementSettleJob = scope.launch {
            delay(ENHANCEMENT_SETTLE_DELAY_MS)
            if (!isVisibleOnScreen()) return@launch

            // 需求 1：登记插队（提升可见页优先级 + 待重建预加载窗口）。登记时不做任何打断，
            // 真正提升/重建要等当前正在增强的图片完成后由 worker 执行（见 ImageEnhancer.processRequest.finally）。
            ImageEnhancer.settle(
                page.index,
                page.enhancementKeySuffix,
                extraPage?.index,
                extraPage?.enhancementKeySuffix.orEmpty(),
            )
            // 仍以高优先级入队，但 preempt=false：不打断正在增强的任务，仅让本页在队列中排最前
            ReaderEnhancement.request(context, page, highPriority = true, preempt = false)
            val secondary = extraPage
            if (secondary != null) {
                ReaderEnhancement.request(context, secondary, highPriority = true, preempt = false)
            }

            enhancementRefreshJob?.cancel()
            enhancementRefreshJob = scope.launch {
                while (isActive) {
                    delay(ENHANCEMENT_POLL_INTERVAL_MS)
                    if (ImageEnhancer.hasRequest(mangaId, chapterId, page.index, page.enhancementKeySuffix)) {
                        continue
                    }
                    // 处理结束后已经生成放大结果则重新加载页面，否则放弃等待
                    if (!showingEnhancedImage &&
                        page.status == Page.State.Ready &&
                        ReaderEnhancement.cachedFile(context, page) != null
                    ) {
                        // 新图先插到最底层加载，原图留在上层遮挡，加载完成后才移除原图，避免黑屏
                        setNextImageInsertAtBottom()
                        setImage()
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}

/** 图像增强处理完成后刷新画面的轮询间隔。 */
private const val ENHANCEMENT_POLL_INTERVAL_MS = 700L

/** 翻页后停留多久才把当前页提到最高优先级处理，避免快速翻页时反复打断增强。 */
private const val ENHANCEMENT_SETTLE_DELAY_MS = 1000L
