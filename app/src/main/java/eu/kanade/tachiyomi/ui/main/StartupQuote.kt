package eu.kanade.tachiyomi.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val STARTUP_QUOTE_DURATION = 2000L
private const val STARTUP_QUOTE_FADE = 400

private val STARTUP_QUOTES = listOf(
    "全世界无产者，联合起来！",
    "让统治阶级在共产主义革命面前发抖吧。无产者在这个革命中失去的只是锁链，他们获得的将是整个世界。",
    "哲学家们只是用不同的方式解释世界，而问题在于改变世界。",
    "资本来到世间，从头到脚，每个毛孔都滴着血和肮脏的东西。",
    "忘记过去，就意味着背叛。",
    "星星之火，可以燎原。",
    "为人民服务。",
    "自己动手，丰衣足食。",
)

@Composable
fun StartupQuoteOverlay(onDone: () -> Unit) {
    val quote = remember { STARTUP_QUOTES.random() }
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(STARTUP_QUOTE_FADE),
        label = "startupQuoteAlpha",
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(STARTUP_QUOTE_DURATION)
        visible = false
        delay(STARTUP_QUOTE_FADE.toLong())
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = quote,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(32.dp),
        )
    }
}
