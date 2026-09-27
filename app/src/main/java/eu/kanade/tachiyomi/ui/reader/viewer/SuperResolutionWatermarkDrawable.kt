package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * 叠加在图片之上的 “Super-Resolution” 水印。
 *
 * 作为 [SubsamplingScaleImageView] 的前景绘制：图片视图每次重绘都会一并画出，
 * 所以水印位置始终跟随当前缩放与平移（把图片坐标换算成视图坐标），
 * 且字号固定，放大后依旧清晰。
 * 双页合并显示时，在合并图的左右两个半页左上角各画一个。
 */
class SuperResolutionWatermarkDrawable(
    private val imageView: SubsamplingScaleImageView,
) : Drawable() {

    /** 是否显示水印：仅当画面是增强成品时开启。 */
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                imageView.invalidate()
            }
        }

    /** 当前显示的单页数量：双页合并显示时为 2，两张图的左上角各画一个。 */
    var pageCount: Int = 1
        set(value) {
            val coerced = value.coerceIn(1, 2)
            if (field != coerced) {
                field = coerced
                imageView.invalidate()
            }
        }

    private val density = imageView.resources.displayMetrics.density
    private val textSizePx = TEXT_SIZE_DP * density
    private val marginPx = MARGIN_DP * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 210, 210, 210)
        textSize = textSizePx
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(SHADOW_RADIUS_DP * density, 0f, 0f, Color.argb(120, 0, 0, 0))
    }

    override fun draw(canvas: Canvas) {
        if (!enabled || !imageView.isReady) return

        val step = imageView.sWidth / pageCount.toFloat()
        for (index in 0 until pageCount) {
            val point = imageView.sourceToViewCoord(step * index + marginPx, marginPx) ?: continue
            canvas.drawText(WATERMARK_TEXT, point.x, point.y + textSizePx, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val WATERMARK_TEXT = "Super-Resolution"
        const val TEXT_SIZE_DP = 11f
        const val MARGIN_DP = 4f
        const val SHADOW_RADIUS_DP = 1.5f
    }
}
