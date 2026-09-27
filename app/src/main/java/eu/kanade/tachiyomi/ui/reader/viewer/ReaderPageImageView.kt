package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import java.io.InputStream
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV.get()
    }

    private var pageView: View? = null

    /** 图片左上角的 “Super-Resolution” 水印，仅增强成品显示时开启。 */
    private var watermarkDrawable: SuperResolutionWatermarkDrawable? = null

    /** 水印的期望状态：图片视图被重建（翻页后重新绑定）时用它恢复，避免水印丢失。 */
    private var watermarkVisible = false
    private var watermarkPages = 1

    /** 下一次设置图片时把新图插到最底层加载，原图留在上层遮挡，加载完成后再移除原图。 */
    private var insertNewImageAtBottom = false

    /** 底层插入模式下正在退场的原图视图，新图就绪后回收。 */
    private var swapOutgoingView: View? = null

    /**
     * 让下一次设置图片时把新图先插到最底层加载。
     *
     * 原图会一直显示在上层，等新图真正加载完成后才被移除，
     * 因此从原图切换到增强成品时不会黑屏，也不需要额外的过渡画面。
     */
    fun setNextImageInsertAtBottom() {
        insertNewImageAtBottom = true
    }

    /** 当前是否已经有一张加载完成的画面（换图时据此判断能否用底层插入来遮挡）。 */
    val hasReadyImage: Boolean
        get() = when (val view = pageView) {
            is SubsamplingScaleImageView -> view.isReady
            is AppCompatImageView -> view.drawable != null
            else -> false
        }

    /** 底层新图就绪后：同步缩放位置，再隐藏并回收上层的原图。 */
    private fun finishBottomInsertSwap(newView: SubsamplingScaleImageView) {
        val outgoing = swapOutgoingView ?: return
        swapOutgoingView = null
        syncScaleFrom(outgoing, newView)
        outgoing.isVisible = false
        removeView(outgoing)
        when (outgoing) {
            is SubsamplingScaleImageView -> outgoing.recycle()
            is AppCompatImageView -> outgoing.dispose()
        }
    }

    /** 底层新图加载失败时撤销插入，让原图继续显示。 */
    private fun cancelBottomInsertSwap(failedView: View) {
        val outgoing = swapOutgoingView
        swapOutgoingView = null
        removeView(failedView)
        if (outgoing != null) {
            outgoing.isVisible = true
            pageView = outgoing
        }
    }

    /** 把旧图上用户当前的缩放与中心点按比例映射到新图，避免切换时视野跳变。 */
    private fun syncScaleFrom(old: View, new: SubsamplingScaleImageView) {
        val oldView = old as? SubsamplingScaleImageView ?: return
        val center = oldView.center ?: return
        if (!oldView.isReady || oldView.sWidth <= 0 || oldView.sHeight <= 0) return
        if (new.sWidth <= 0 || new.sHeight <= 0) return

        val zoomFactor = oldView.scale / oldView.minScale
        val mappedCenter = PointF(
            center.x / oldView.sWidth * new.sWidth,
            center.y / oldView.sHeight * new.sHeight,
        )
        val mappedScale = (new.minScale * zoomFactor).coerceIn(new.minScale, new.maxScale)
        new.setScaleAndCenter(mappedScale, mappedCenter)
    }

    private var config: Config? = null

    /**
     * 是否禁止双击缩放（设置 → 常规 → 禁止双击缩放）。
     *
     * 默认不禁止；[eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder] 会覆盖成实时读取
     * 阅读器配置，因此改设置立刻生效，而不需要重建页面。
     */
    protected open val disableDoubleTapZoom: Boolean = false

    /** 自己用来识别双击的手势识别器，参数与 SSIV 内部使用的一致。 */
    private val doubleTapDetector: GestureDetector by lazy {
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    blockDoubleTapZoom = true
                    return true
                }
            },
        )
    }

    /** 本轮手势里已经识别到双击，需要在事件交给 SSIV 之前临时关掉它的缩放能力。 */
    private var blockDoubleTapZoom = false

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config, applyInitialScale = true)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val config = config
        if (config != null &&
            config.landscapeZoom &&
            config.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                (animateScaleAndCenter(targetScale, point) ?: return@postDelayed)
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    /**
     * 拦截 [SubsamplingScaleImageView] 的双击缩放。
     *
     * SSIV 没有提供「只禁止双击缩放」的开关：`setZoomEnabled(false)` 会把双指缩放一起禁掉，
     * 而 `setDoubleTapZoomScale()` 只是改变缩放目标，放大状态下双击依然会缩回整页。
     * 所以这里自己识别双击：在双击的第二下 ACTION_DOWN（正是 SSIV 内部 onDoubleTap 被调用的时刻）
     * 之前把 zoomEnabled 临时置为 false，让 SSIV 的 onDoubleTap 整段跳过，然后立刻把这个标志恢复。
     * 因为 SSIV 的 onTouchEvent 是同步执行的，恢复动作排在它之后，所以只有双击被屏蔽，
     * 双指缩放、平移、单击都不受影响。
     *
     * @return 与其它 OnTouchListener 一样，返回 false 表示不拦截事件，继续交给 SSIV 处理。
     */
    private fun onTouchEventForDoubleTapZoom(view: SubsamplingScaleImageView, event: MotionEvent): Boolean {
        if (!disableDoubleTapZoom) return false

        doubleTapDetector.onTouchEvent(event)
        if (blockDoubleTapZoom) {
            blockDoubleTapZoom = false
            view.setZoomEnabled(false)
            view.post { view.setZoomEnabled(true) }
        }
        return false
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        val insertAtBottom = insertNewImageAtBottom
        if (pageView is SubsamplingScaleImageView && !insertAtBottom) return
        val outgoing = pageView
        if (!insertAtBottom) {
            removeView(outgoing)
        }

        val newView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
            if (!isWebtoon) {
                // 条漫的 SSIV 本身忽略触摸（手势由 WebtoonRecyclerView 处理），只有单页式需要拦截双击
                setOnTouchListener { view, event ->
                    this@ReaderPageImageView.onTouchEventForDoubleTapZoom(view as SubsamplingScaleImageView, event)
                }
            }
            // 水印作为图片视图的前景：随图片重绘，因而始终跟随缩放与平移
            watermarkDrawable = SuperResolutionWatermarkDrawable(this).also { foreground = it }
            this@ReaderPageImageView.applyWatermarkState()
        }

        if (insertAtBottom) {
            // 新图先插到最底层加载，原图继续显示在上层，等新图就绪后再移除原图
            insertNewImageAtBottom = false
            swapOutgoingView = outgoing
            addView(newView, 0, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            addView(newView, MATCH_PARENT, MATCH_PARENT)
        }
        pageView = newView
    }

    /**
     * 设置图片左上角的 “Super-Resolution” 水印。
     *
     * @param visible 仅当画面是增强成品时为 true。
     * @param pages 双页合并显示时传 2，两张图片的左上角各显示一个。
     */
    fun setSuperResolutionWatermark(visible: Boolean, pages: Int = 1) {
        watermarkVisible = visible
        watermarkPages = pages
        applyWatermarkState()
    }

    /** 把记录的水印状态应用到当前的水印绘制对象。 */
    private fun applyWatermarkState() {
        watermarkDrawable?.apply {
            pageCount = watermarkPages
            enabled = watermarkVisible
        }
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?, applyInitialScale: Boolean) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        // 底层插入换图时，初始缩放由 syncScaleFrom（继承旧图视角）负责，这里不再二次 setScaleAndCenter，
        // 否则同一帧内连续设置两次缩放会让水印（sourceToViewCoord）瞬间跳变一次。
        if (!applyInitialScale) return
        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // 底层插入换图时初始缩放交由 syncScaleFrom 继承旧图视角，避免二次缩放导致水印瞬跳
                    setupZoom(config, applyInitialScale = swapOutgoingView == null)
                    // 底层插入模式：新图就绪后再收掉上层原图，切换过程始终有画面
                    this@ReaderPageImageView.finishBottomInsertSwap(this@apply)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.cancelBottomInsertSwap(this@apply)
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)
        // 动图不走底层插入换图，清掉标记避免影响下一次加载
        insertNewImageAtBottom = false

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            // 禁止双击缩放：双击不再放大/还原，单击（菜单）行为保持不变
                            if (disableDoubleTapZoom) return true
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
