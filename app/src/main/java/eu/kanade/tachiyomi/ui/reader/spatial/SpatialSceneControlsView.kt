package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Space-depth controls as a single vertical menu, centered vertically on the right edge.
 * Rows: 旋转锚点 / 陀螺仪归零 / 灵敏度 / 景深 / X / Y / Z.
 * Tapping a parameter row reveals its slider inline below the row.
 */
class SpatialSceneControlsView(context: Context) : LinearLayout(context) {
    private val anchorRow = menuRow()
    private val gyroRow = menuRow()

    private val sensitivitySeekBar = seekBar(SENSITIVITY_STEPS)
    private val sensitivitySliderRow = sliderRow(sensitivitySeekBar)
    private val sensitivityRow = menuRow()

    private val depthSeekBar = seekBar(DEPTH_STEPS)
    private val depthSliderRow = sliderRow(depthSeekBar)
    private val depthRow = menuRow()

    private val xSeekBar = seekBar(ROTATION_ANGLE_STEPS)
    private val xSliderRow = sliderRow(xSeekBar)
    private val xRow = menuRow()

    private val ySeekBar = seekBar(ROTATION_ANGLE_STEPS)
    private val ySliderRow = sliderRow(ySeekBar)
    private val yRow = menuRow()

    private val zSeekBar = seekBar(ROTATION_ANGLE_STEPS)
    private val zSliderRow = sliderRow(zSeekBar)
    private val zRow = menuRow()

    private val collapseRow = menuRow()
    private var onDismissRequested: (() -> Unit)? = null
    private var anchorIdleText = ""

    init {
        orientation = VERTICAL
        setPadding(10.dp, 6.dp, 10.dp, 6.dp)
        background = menuBackground()
        // ── 旋转锚点 ──
        addView(anchorRow, rowParams())
        addDivider()
        // ── 陀螺仪归零 ──
        addView(gyroRow, rowParams())
        addDivider()
        // ── 灵敏度 ──
        addView(sensitivityRow, rowParams())
        addView(sensitivitySliderRow, rowParams())
        addDivider()
        // ── 景深 ──
        addView(depthRow, rowParams())
        addView(depthSliderRow, rowParams())
        addDivider()
        // ── X ──
        addView(xRow, rowParams())
        addView(xSliderRow, rowParams())
        addDivider()
        // ── Y ──
        addView(yRow, rowParams())
        addView(ySliderRow, rowParams())
        addDivider()
        // ── Z ──
        addView(zRow, rowParams())
        addView(zSliderRow, rowParams())
        addDivider()
        // ── 收起幕布（>>）──
        addView(collapseRow, rowParams())
    }

    fun configure(
        sensitivity: Float,
        depthStrength: Float,
        rotationAngleX: Float,
        rotationAngleY: Float,
        rotationAngleZ: Float,
        anchorText: String,
        anchorPickText: String,
        sensitivityText: (Float) -> String,
        depthText: (Float) -> String,
        rotationText: (String, Float) -> String,
        rotationButtonText: String,
        rotationResetText: String,
        gyroResetText: String,
        collapseText: String,
        onDismissRequested: () -> Unit,
        onAnchorRequested: () -> Unit,
        onGyroscopeReset: () -> Unit,
        onSensitivityChanged: (Float) -> Unit,
        onDepthStrengthChanged: (Float) -> Unit,
        onRotationAnglesChanged: (Float, Float, Float) -> Unit,
    ) {
        anchorIdleText = anchorText
        this.onDismissRequested = onDismissRequested
        collapseRow.text = collapseText
        collapseRow.setOnClickListener {
            collapseAll()
            // 菜单收入屏幕外侧
            animate()
                .translationX(resources.displayMetrics.widthPixels.toFloat())
                .setDuration(260)
                .setInterpolator(AccelerateInterpolator())
                .start()
            onDismissRequested()
        }
        anchorRow.text = anchorText
        anchorRow.setOnClickListener {
            collapseAll()
            anchorRow.text = anchorPickText
            onAnchorRequested()
        }
        gyroRow.text = gyroResetText
        gyroRow.setOnClickListener {
            collapseAll()
            onGyroscopeReset()
        }

        val initialSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        sensitivityRow.text = sensitivityText(initialSensitivity)
        sensitivityRow.setOnClickListener { toggleRow(sensitivityRow, sensitivitySliderRow) }
        sensitivitySeekBar.setOnSeekBarChangeListener(
            sliderListener { progress ->
                val value = MIN_SENSITIVITY + progress / 100f
                sensitivityRow.text = sensitivityText(value)
                onSensitivityChanged(value)
            },
        )
        sensitivitySeekBar.progress = ((initialSensitivity - MIN_SENSITIVITY) * 100f).toInt()

        val initialDepth = depthStrength.coerceIn(MIN_DEPTH, MAX_DEPTH)
        depthRow.text = depthText(initialDepth)
        depthRow.setOnClickListener { toggleRow(depthRow, depthSliderRow) }
        depthSeekBar.setOnSeekBarChangeListener(
            sliderListener { progress ->
                val value = MIN_DEPTH + progress / 100f
                depthRow.text = depthText(value)
                onDepthStrengthChanged(value)
            },
        )
        depthSeekBar.progress = ((initialDepth - MIN_DEPTH) * 100f).toInt()

        var currentX = rotationAngleX.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        var currentY = rotationAngleY.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        var currentZ = rotationAngleZ.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        fun updateRotationUi() {
            xRow.text = rotationText("X", currentX)
            yRow.text = rotationText("Y", currentY)
            zRow.text = rotationText("Z", currentZ)
        }
        fun notifyRotationChanged() {
            updateRotationUi()
            onRotationAnglesChanged(currentX, currentY, currentZ)
        }
        xRow.setOnClickListener { toggleRow(xRow, xSliderRow) }
        yRow.setOnClickListener { toggleRow(yRow, ySliderRow) }
        zRow.setOnClickListener { toggleRow(zRow, zSliderRow) }
        xSeekBar.setOnSeekBarChangeListener(angleListener { value -> currentX = value; notifyRotationChanged() })
        ySeekBar.setOnSeekBarChangeListener(angleListener { value -> currentY = value; notifyRotationChanged() })
        zSeekBar.setOnSeekBarChangeListener(angleListener { value -> currentZ = value; notifyRotationChanged() })
        xSeekBar.progress = angleToProgress(currentX)
        ySeekBar.progress = angleToProgress(currentY)
        zSeekBar.progress = angleToProgress(currentZ)
        updateRotationUi()
    }

    fun completeAnchorSelection() {
        anchorRow.text = anchorIdleText
    }

    private fun toggleRow(row: TextView, sliderRow: LinearLayout) {
        val opening = sliderRow.visibility != View.VISIBLE
        if (opening) collapseAll()
        sliderRow.visibility = if (opening) View.VISIBLE else View.GONE
    }

    private fun collapseAll() {
        anchorRow.text = anchorIdleText
        sensitivitySliderRow.visibility = View.GONE
        depthSliderRow.visibility = View.GONE
        xSliderRow.visibility = View.GONE
        ySliderRow.visibility = View.GONE
        zSliderRow.visibility = View.GONE
    }

    // ── 组件工厂 ──

    private fun menuRow() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        minHeight = 40.dp
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun sliderRow(slider: SeekBar) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        isClickable = true
        val sliderPadding = 6.dp
        setPadding(sliderPadding, 2.dp, sliderPadding, 2.dp)
        addView(
            slider,
            LayoutParams(0, 34.dp, 1f),
        )
    }

    private fun seekBar(max: Int) = SeekBar(context).apply {
        this.max = max
        splitTrack = false
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressTintList = ColorStateList.valueOf(Color.rgb(166, 200, 255))
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }

    private fun sliderListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) { /* do nothing */ }
        override fun onStopTrackingTouch(seekBar: SeekBar?) { /* do nothing */ }
    }

    private fun angleListener(onChanged: (Float) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onChanged(MIN_ROTATION_ANGLE + progress / 10f)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) { /* do nothing */ }
        override fun onStopTrackingTouch(seekBar: SeekBar?) { /* do nothing */ }
    }

    private fun angleToProgress(value: Float): Int =
        ((value.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE) - MIN_ROTATION_ANGLE) * 10f).toInt()

    private fun rowParams() = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun addDivider() {
        addView(
            View(context).apply {
                setBackgroundColor(Color.argb(90, 255, 255, 255))
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp),
        )
    }

    private fun menuBackground() = GradientDrawable().apply {
        cornerRadius = 16.dp.toFloat()
        setColor(Color.argb(230, 28, 27, 31))
        setStroke(1.dp, Color.argb(120, 255, 255, 255))
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3f
        const val SENSITIVITY_STEPS = 250
        const val MIN_DEPTH = 0.5f
        const val MAX_DEPTH = 2f
        const val DEPTH_STEPS = 150
        const val MIN_ROTATION_ANGLE = 0f
        const val MAX_ROTATION_ANGLE = 20f
        const val ROTATION_ANGLE_STEPS = 200
    }
}