package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val textStyle = overlayTextStyle()
    val textMeasurer = rememberTextMeasurer()
    val fourCharWidth = with(LocalDensity.current) {
        textMeasurer.measure("0000", textStyle).size.width.toDp()
    }

    ReaderOverlayText(
        text = time,
        modifier = modifier.padding(end = fourCharWidth),
    )
}

private fun currentTimeString(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private const val MILLIS_PER_MINUTE = 60_000L
private const val PROGRESS_POLL_INTERVAL_MS = 200L

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
        color = Color(45, 45, 45),
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
