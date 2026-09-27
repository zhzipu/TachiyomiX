package eu.kanade.presentation.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 返回垂直坐标 [y]（相对 LazyColumn 视口）下方可见 item 的 index，未命中返回 null。
 */
fun LazyListState.itemIndexAt(y: Float): Int? =
    layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y <= it.offset + it.size }
        ?.index

/**
 * 返回点 ([x], [y])（相对 LazyGrid 视口）下方可见 item 的 index，未命中返回 null。
 */
fun LazyGridState.itemIndexAt(x: Float, y: Float): Int? =
    layoutInfo.visibleItemsInfo
        .firstOrNull {
            x >= it.offset.x && x <= it.offset.x + it.size.width &&
                y >= it.offset.y && y <= it.offset.y + it.size.height
        }
        ?.index

/**
 * 当 [active] 为 true 且 [trigger] 变化时，延迟 [delayMillis] 后令该 item 闪烁两下
 * （透明度两次下降再恢复），整个闪烁过程持续 [durationMillis]。
 */
@Composable
fun Modifier.flash(
    active: Boolean,
    trigger: Int,
    delayMillis: Long = 500,
    durationMillis: Long = 1000,
): Modifier {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (!active) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(delayMillis)
        repeat(2) {
            alpha.snapTo(0.25f)
            alpha.animateTo(1f, tween((durationMillis / 2).toInt()))
        }
        alpha.snapTo(1f)
    }
    return graphicsLayer { this.alpha = alpha.value }
}

/**
 * 手指指向该项目时绘制缓慢闪烁的边框（颜色取外观设置的主题色），
 * [active] 为 false 时不绘制边框。
 */
@Composable
fun Modifier.pointerHighlight(
    active: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "indexBarHighlight")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "indexBarHighlightAlpha",
    )
    return border(
        width = 2.dp,
        color = color.copy(alpha = alpha),
        shape = RoundedCornerShape(6.dp),
    )
}

/**
 * 该区域内发生任何按下/滑动操作时回调 [onTouch]（用于清除定位后的高亮）。
 *
 * 使用 Initial 传递阶段且不消费事件，所以不会影响子节点的点击与滚动。
 */
@Composable
fun Modifier.onAnyTouch(onTouch: () -> Unit): Modifier {
    val currentOnTouch by rememberUpdatedState(onTouch)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed }) {
                    currentOnTouch()
                }
            }
        }
    }
}
