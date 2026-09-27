package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay

/**
 * 首字母索引侧边栏（类似通讯录右侧的 A-Z 索引条）。
 *
 * 贴边显示，不会遮挡内容；当前字母以主题色纵向胶囊（竖胶囊）高亮。
 *
 * 交互：
 *  - 触摸区域只有贴边的一条竖带，不占用列表布局空间（叠在列表之上），
 *    触摸宽度大于视觉宽度，方便按住与滑入列表；
 *  - 手指在索引条上上下滑动：跟随所在高度切换字母，并显示居中气泡；
 *  - 手指按住不放滑入列表：索引条锁定在离开索引条时的字母，
 *    此时列表保持首字母排序，可滑到目标项后松手；
 *  - 松手时通过 [onDragFinished] 通知调用方定位：入参为手指松开的位置
 *    （已换算到索引条所在父容器、也就是列表视口的坐标系），未滑入列表时为 null；
 *    返回值表示是否命中了某一项；
 *  - 未命中任何项（含未滑入列表）时用 [hintText] 提示用户；
 *  - 手指在列表内移动时通过 [onDragMoved] 上报位置（不在列表内或松手时为 null），
 *    供调用方高亮手指指中的项目；
 *  - 手指所在区域通过 [onDragTargetChange] 上报，供调用方做边缘滚动等交互；
 *  - 松手后 [onActiveLetterChange] 收到 null，列表恢复原排序。
 */
val LETTER_INDEX_LIST: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/** 索引条拖动时手指所在的区域 */
enum class IndexBarDragTarget {
    /** 手指还在索引条上 */
    Bar,

    /** 手指已滑入列表 */
    List,

    /** 手指滑到列表上方（如书架的封面分类栏） */
    ListTop,

    /** 手指滑到列表下方（如书架底部导航栏） */
    ListBottom,
}

/** 字母列视觉宽度（贴边显示） */
private val LetterColumnWidth = 16.dp

/** 实际触摸宽度：在视觉宽度基础上向列表方向多扩展两个视觉宽度，约 48dp */
private val TouchWidth = LetterColumnWidth * 3

@Composable
fun LetterIndexBar(
    modifier: Modifier = Modifier,
    letters: List<String> = LETTER_INDEX_LIST,
    position: Alignment = Alignment.CenterEnd,
    activeLetter: String? = null,
    hintText: String? = null,
    bottomZoneHeight: Dp = 0.dp,
    onActiveLetterChange: (String?) -> Unit = {},
    onDragFinished: ((Offset?) -> Boolean)? = null,
    onDragTargetChange: ((IndexBarDragTarget?) -> Unit)? = null,
    onDragMoved: ((Offset?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isBarOnLeft = position == Alignment.CenterStart
    // 手势回调在 pointerInput 里长期存活，用 rememberUpdatedState 保证读取到最新的 lambda
    val currentOnActiveLetterChange by rememberUpdatedState(onActiveLetterChange)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    val currentOnDragTargetChange by rememberUpdatedState(onDragTargetChange)
    val currentOnDragMoved by rememberUpdatedState(onDragMoved)
    // 触摸条相对父容器的偏移，用于把手指坐标换算到列表视口坐标系
    var barOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 触摸竖带：只有这块区域拦截手势，其余区域点击/滚动照常交给列表
        Box(
            modifier = Modifier
                .align(position)
                .fillMaxHeight()
                .width(TouchWidth)
                .onGloballyPositioned { barOffset = it.positionInParent() }
                .pointerInput(letters, position, bottomZoneHeight) {
                    val touchWidthPx = TouchWidth.toPx()
                    // 底部这段高度视为"列表下方"：没有底部栏（如平板布局）时，
                    // 列表会在这段区域内自己画"下移"提示条
                    val bottomZonePx = bottomZoneHeight.toPx()
                    val letterHeight = size.height / letters.size
                    fun letterIndexFor(y: Float): Int =
                        (y / letterHeight).toInt().coerceIn(0, letters.size - 1)
                    fun isOnBar(x: Float): Boolean = x in 0f..touchWidthPx
                    fun toListPosition(p: Offset): Offset = Offset(p.x + barOffset.x, p.y + barOffset.y)
                    fun targetFor(p: Offset): IndexBarDragTarget = when {
                        isOnBar(p.x) -> IndexBarDragTarget.Bar
                        p.y < 0f -> IndexBarDragTarget.ListTop
                        p.y > size.height - bottomZonePx -> IndexBarDragTarget.ListBottom
                        else -> IndexBarDragTarget.List
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 索引条独占本次手势：消费掉事件，避免上层容器（如浏览页的
                        // HorizontalPager）同时识别为左右滑动而切换卡片
                        down.consume()
                        val touchSlop = viewConfiguration.touchSlop
                        var target = targetFor(down.position)
                        var dragged = false
                        var completed = false
                        var located = false
                        var lastPosition = down.position
                        currentOnActiveLetterChange(letters[letterIndexFor(down.position.y)])
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                lastPosition = change.position
                                if ((change.position - down.position).getDistance() > touchSlop) {
                                    dragged = true
                                }
                                val newTarget = targetFor(change.position)
                                if (newTarget != target) {
                                    // 滑入列表/列表上下方：锁定当前字母，不再随上下坐标变化
                                    currentOnDragTargetChange?.invoke(newTarget)
                                }
                                if (newTarget == IndexBarDragTarget.Bar) {
                                    // 仍在（或回到）索引条上：跟随上下位置切换字母
                                    currentOnActiveLetterChange(letters[letterIndexFor(change.position.y)])
                                }
                                target = newTarget
                                // 上报手指在列表中的位置，供调用方高亮指中的项目
                                currentOnDragMoved?.invoke(
                                    if (newTarget == IndexBarDragTarget.List) {
                                        toListPosition(change.position)
                                    } else {
                                        null
                                    },
                                )
                                change.consume()
                                if (!change.pressed) break
                            }
                            completed = true
                            located = currentOnDragFinished?.invoke(
                                // 只有松手在列表内才尝试定位；在上下边缘（上移/下移区）松手一律不定位
                                if (target == IndexBarDragTarget.List) toListPosition(lastPosition) else null,
                            ) == true
                        } finally {
                            // 无论正常结束还是被取消，都要解除锁定，避免列表卡在排序/禁止滑动状态
                            currentOnActiveLetterChange(null)
                            currentOnDragTargetChange?.invoke(null)
                            // 定位成功时保留高亮边框（由 onDragFinished 设置），其余情况清除
                            if (!located) {
                                currentOnDragMoved?.invoke(null)
                            }
                        }
                        // 只有真正滑动过（非点按）、松手在列表内且没有落在某项上时才提示
                        if (completed && dragged && !located && target == IndexBarDragTarget.List) {
                            hintText?.let { context.toast(it) }
                        }
                    }
                },
        ) {
            // 视觉字母列
            Column(
                modifier = Modifier
                    .align(if (isBarOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(LetterColumnWidth),
            ) {
                letters.forEach { letter ->
                    val active = letter == activeLetter
                    // 每个字母占一条等高竖带，高亮为纵向胶囊
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .let {
                                if (active) {
                                    it
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primary)
                                } else {
                                    it
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = letter,
                            textAlign = TextAlign.Center,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = 12.sp,
                        )
                    }
                }
            }
        }

        // 字母气泡：半透明矩形背景 + 当前字母。
        // 索引条在左侧时气泡位于右上方，在右侧时位于左上方，避免挡住索引条本身
        if (activeLetter != null) {
            Box(
                modifier = Modifier
                    .align(if (isBarOnLeft) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = 6.dp, start = 8.dp, end = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = activeLetter,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 手指停在顶部/底部区域时每次滚动的距离 */
private val EdgeScrollStep = 5.dp

/** 索引条拖动时，是否需要在底部导航栏上显示"下移"遮罩（由内容区写入、底部栏读取） */
val LocalIndexBarBottomOverlay = compositionLocalOf { mutableStateOf(false) }

/**
 * 是否已有底部栏（手机布局的底部导航栏）在显示"下移"区域。
 * 为 false 时（平板布局、或底部导航栏被隐藏），由列表自己绘制"下移"区域。
 */
val LocalIndexBarBottomBarVisible = compositionLocalOf { true }

/** 索引条拖到上下边缘时的提示条：外观色背景 + 白色加大文字 */
@Composable
fun IndexBarEdgeHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 手指还在索引条上滑动时，给列表盖一层半透明遮罩并居中提示下一步操作；
 * 手指滑入列表后调用方应传入 false 让它消失。
 */
@Composable
fun IndexBarLocateHint(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 索引条拖到列表上方/下方时的边缘交互：
 *  - [dragTarget] 为列表上方/下方时缓慢滚动列表（[scrollBy] 传列表的滚动方法）；
 *  - 手指在列表内（含上下边缘）期间，让底部导航栏显示"下移"遮罩。
 */
@Composable
fun IndexBarEdgeScroller(
    dragTarget: IndexBarDragTarget?,
    scrollBy: suspend (Float) -> Float,
) {
    val bottomOverlay = LocalIndexBarBottomOverlay.current
    val draggingInList = dragTarget != null && dragTarget != IndexBarDragTarget.Bar
    DisposableEffect(bottomOverlay, draggingInList) {
        bottomOverlay.value = draggingInList
        onDispose { bottomOverlay.value = false }
    }
    val zone = when (dragTarget) {
        IndexBarDragTarget.ListTop -> -1
        IndexBarDragTarget.ListBottom -> 1
        else -> 0
    }
    val density = LocalDensity.current
    val currentScrollBy by rememberUpdatedState(scrollBy)
    LaunchedEffect(zone) {
        if (zone == 0) return@LaunchedEffect
        val step = with(density) { EdgeScrollStep.toPx() } * zone
        while (true) {
            delay(16)
            currentScrollBy(step)
        }
    }
}
