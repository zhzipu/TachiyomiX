package eu.kanade.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReaderPageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
    /** 双页跨页时的指示文本「左页-总页数-右页」（如 1-18-2）；为空时走单页格式「当前页 / 总页数」。 */
    indicatorText: String = "",
) {
    if (currentPage <= 0 || totalPages <= 0) return

    ReaderOverlayText(
        text = indicatorText.ifBlank { "$currentPage / $totalPages" },
        modifier = modifier,
    )
}

/** 阅读器**顶端左侧**的系统时间。 */
@Composable
fun ReaderSystemTimeIndicator(
    modifier: Modifier = Modifier,
) {
    val time by produceState(initialValue = currentTimeString()) {
        while (true) {
            val nextMinute = (System.currentTimeMillis() / MILLIS_PER_MINUTE + 1) * MILLIS_PER_MINUTE
            delay(nextMinute - System.currentTimeMillis())
            value = currentTimeString()
        }
    }

    // 起始留出「0000」的宽度，避免压到系统状态栏左侧那一列图标。
    ReaderOverlayText(
        text = time,
        modifier = modifier.padding(start = statusBarIconColumnWidth()),
    )
}

/** 阅读器**顶端右侧**的电量图标 + 剩余电量百分比。 */
@Composable
fun ReaderBatteryStatusIndicator(
    modifier: Modifier = Modifier,
) {
    val battery = rememberBatteryState()

    // 末尾留出「0000」的宽度，避免压到系统状态栏右侧那一列图标。
    ReaderBatteryIndicator(
        state = battery,
        modifier = modifier.padding(end = statusBarIconColumnWidth()),
    )
}

/** 系统状态栏图标列的宽度（按「0000」估）：顶端指示器靠它躲开状态栏那一列。 */
@Composable
private fun statusBarIconColumnWidth(): Dp {
    val textStyle = overlayTextStyle()
    val textMeasurer = rememberTextMeasurer()
    return with(LocalDensity.current) {
        textMeasurer.measure("0000", textStyle).size.width.toDp()
    }
}

/** 剩余电量与是否正在充电；[BatteryState.level] 为 -1 表示还没读到。 */
private data class BatteryState(
    val level: Int,
    val charging: Boolean,
) {
    companion object {
        val Unknown = BatteryState(level = -1, charging = false)

        fun fromIntent(intent: Intent?): BatteryState? {
            intent ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return BatteryState(
                level = (level * 100f / scale).roundToInt().coerceIn(0, 100),
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
    }
}

/**
 * 读电量。
 *
 * [Intent.ACTION_BATTERY_CHANGED] 是 sticky 系统广播：注册那一刻就会把当前值回调回来，
 * 之后电量变化、插拔充电器也会继续收到，不需要轮询。
 */
@Composable
private fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState.Unknown) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                BatteryState.fromIntent(intent)?.let { state = it }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        BatteryState.fromIntent(sticky)?.let { state = it }
        onDispose { context.unregisterReceiver(receiver) }
    }

    return state
}

/** 电池图标 + 剩余电量百分比。 */
@Composable
private fun ReaderBatteryIndicator(
    state: BatteryState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BATTERY_ICON_GAP),
    ) {
        BatteryIcon(level = state.level, charging = state.charging)
        if (state.level >= 0) {
            ReaderOverlayText(text = "${state.level}%")
        }
    }
}

/**
 * 手画电池图标：外壳 + 正极 + 按电量比例的填充，填充底下垫一层纯黑底衬。
 *
 * 底衬**只垫在电量条下面**（比电量条四周各大出 [BATTERY_BAR_BACKER_PADDING]）：外壳与电量条都是浅色
 * （[BATTERY_OUTLINE_COLOR]），压在白色漫画页上会糊成一片、电量读不出来；垫黑之后页面是白是黑都看得清，
 * 又不至于像铺满整块底板那样把黑面积撑大。
 *
 * 电池本体沿用「先描一道粗深色、再压一道细亮色」的观感，和 [ReaderOverlayText] 的「亮字深边」一致；
 * 充电时填充换色，低于 [LOW_BATTERY_THRESHOLD] 时转警示色。
 */
@Composable
private fun BatteryIcon(level: Int, charging: Boolean) {
    val fillColor = when {
        charging -> BATTERY_CHARGING_COLOR
        level in 0..LOW_BATTERY_THRESHOLD -> BATTERY_LOW_COLOR
        else -> BATTERY_FILL_COLOR
    }

    Canvas(modifier = Modifier.size(BATTERY_ICON_WIDTH, BATTERY_ICON_HEIGHT)) {
        val nubWidth = size.width * NUB_WIDTH_RATIO
        val bodyWidth = size.width - nubWidth
        val corner = size.height * 0.25f
        val nubHeight = size.height * 0.4f
        val outerStroke = 2.5.dp.toPx()
        val innerStroke = 1.2.dp.toPx()

        fun drawShell(color: Color, strokeWidth: Float) {
            drawRoundRect(
                color = color,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(
                    (bodyWidth - strokeWidth).coerceAtLeast(0f),
                    (size.height - strokeWidth).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(bodyWidth, (size.height - nubHeight) / 2),
                size = Size(nubWidth, nubHeight),
                cornerRadius = CornerRadius(nubWidth / 2, nubWidth / 2),
            )
        }

        drawShell(OVERLAY_STROKE_COLOR, outerStroke)
        drawShell(BATTERY_OUTLINE_COLOR, innerStroke)

        if (level > 0) {
            val inset = innerStroke + 1.5.dp.toPx()
            val innerWidth = (bodyWidth - inset * 2).coerceAtLeast(0f)
            val innerHeight = (size.height - inset * 2).coerceAtLeast(0f)
            val fillWidth = innerWidth * (level.coerceIn(0, 100) / 100f)
            if (fillWidth > 0f && innerHeight > 0f) {
                val backer = BATTERY_BAR_BACKER_PADDING.toPx()
                // 先垫黑（比电量条大一圈），再画电量条 —— 黑只出现在电量条这一小片范围内。
                drawRoundRect(
                    color = BATTERY_BAR_BACKER_COLOR,
                    topLeft = Offset((inset - backer).coerceAtLeast(0f), (inset - backer).coerceAtLeast(0f)),
                    size = Size(fillWidth + backer * 2, innerHeight + backer * 2),
                    cornerRadius = CornerRadius(corner / 2 + backer, corner / 2 + backer),
                )
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(inset, inset),
                    size = Size(fillWidth, innerHeight),
                    cornerRadius = CornerRadius(corner / 2, corner / 2),
                )
            }
        }
    }
}

private fun currentTimeString(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private const val MILLIS_PER_MINUTE = 60_000L
private const val PROGRESS_POLL_INTERVAL_MS = 200L

/** 低于这个电量，电池填充转警示色。 */
private const val LOW_BATTERY_THRESHOLD = 15
private const val NUB_WIDTH_RATIO = 0.12f

private val BATTERY_ICON_WIDTH = 22.dp
private val BATTERY_ICON_HEIGHT = 12.dp

/** 电量条下面那层纯黑底衬：白底页面上浅色的外壳与电量条靠它才看得清。 */
private val BATTERY_BAR_BACKER_COLOR = Color.Black

/** 黑底衬比电量条四周各大出多少（让黑在电量条外露一圈，而不是铺满图标）。 */
private val BATTERY_BAR_BACKER_PADDING = 0.75.dp

/** 电池图标与百分比文字之间的距离。 */
private val BATTERY_ICON_GAP = 3.dp

private val OVERLAY_STROKE_COLOR = Color(45, 45, 45)
private val BATTERY_OUTLINE_COLOR = Color(235, 235, 235)
private val BATTERY_FILL_COLOR = Color(235, 235, 235)
private val BATTERY_CHARGING_COLOR = Color(0xFF8BE28B)
private val BATTERY_LOW_COLOR = Color(0xFFFF8A65)

/**
 * 左下角的图像增强处理状态：仅在原生处理进行中显示，空闲时自动隐藏。
 */
@Composable
fun ReaderProcessingStatusIndicator(
    status: ImageEnhancer.ProcessingStatus,
    modifier: Modifier = Modifier,
) {
    if (status.activePageIndex < 0) return

    // 进度只存在于 native 侧，这里按固定间隔轮询，避免为状态显示改动增强处理热路径。
    val progress by produceState(initialValue = 0, status.activePageIndex) {
        while (true) {
            value = Waifu2x.getProgressPercent()
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    val text = if (status.queuedCount > 0) {
        stringResource(
            MR.strings.reader_enhancing_page_queued,
            status.activePageIndex + 1,
            progress,
            status.queuedCount,
        )
    } else {
        stringResource(
            MR.strings.reader_enhancing_page,
            status.activePageIndex + 1,
            progress,
        )
    }

    // 两根固定长度的进度条，只显示进度、不带任何文字；宽度不随上方文字变化
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PROGRESS_BAR_SPACING),
    ) {
        ReaderOverlayText(text = text)
        // 当前正在处理的这张图片的进度
        ProcessingProgressBar(progress = progress / 100f)
        // 本次已处理完成的张数占本次批次张数的进度
        ProcessingProgressBar(
            progress = if (status.sessionBatchCount > 0) {
                status.sessionProcessedCount.toFloat() / status.sessionBatchCount
            } else {
                0f
            },
        )
    }
}

/** 单根进度条：固定长度，只画进度，不显示任何文字信息。 */
@Composable
private fun ProcessingProgressBar(
    progress: Float,
) {
    Box(
        modifier = Modifier
            .width(PROGRESS_BAR_WIDTH)
            .height(PROGRESS_BAR_HEIGHT)
            .background(PROGRESS_BAR_TRACK_COLOR),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(PROGRESS_BAR_COLOR),
        )
    }
}

private val PROGRESS_BAR_WIDTH = 180.dp
private val PROGRESS_BAR_HEIGHT = 3.dp
private val PROGRESS_BAR_SPACING = 3.dp
private val PROGRESS_BAR_COLOR = Color(235, 235, 235)
private val PROGRESS_BAR_TRACK_COLOR = Color(45, 45, 45, 170)

@Composable
private fun overlayTextStyle(): TextStyle = TextStyle(
    color = Color(235, 235, 235),
    fontSize = MaterialTheme.typography.bodySmall.fontSize,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp,
)

@Composable
private fun ReaderOverlayText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val style = overlayTextStyle()
    val strokeStyle = style.copy(
        color = OVERLAY_STROKE_COLOR,
        drawStyle = Stroke(width = 4f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = strokeStyle,
        )
        Text(
            text = text,
            style = style,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPageIndicator(currentPage = 10, totalPages = 69)
        }
    }
}
