package eu.kanade.presentation.more.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.MultiSelectListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.PrefsVerticalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TitleFontSize
import eu.kanade.presentation.more.settings.widget.TrackingPreferenceWidget
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.BaseSliderItem
import tachiyomi.presentation.core.util.collectAsState

val LocalPreferenceHighlighted = compositionLocalOf(structuralEqualityPolicy()) { false }
val LocalPreferenceMinHeight = compositionLocalOf(structuralEqualityPolicy()) { 56.dp }

@Composable
fun StatusWrapper(
    item: Preference.PreferenceItem<*, *>,
    highlightKey: String?,
    content: @Composable () -> Unit,
) {
    val enabled = item.enabled
    val highlighted = item.title == highlightKey
    // 禁用项不再整行隐藏，而是留在列表里**变暗 + 屏蔽交互**：
    // 「存在、但当前不可用」应当看得见（例如代理总开关没开时的「作用域」开关）。
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ITEM_ALPHA)
            .semantics { if (!enabled) disabled() }
            .then(
                if (enabled) {
                    Modifier
                } else {
                    // 只吞「点按」，不阻断拖动：否则在灰掉的这几行上滑动时，外层设置列表滚不动。
                    // detectTapGestures 的行为与普通可点击行一致 —— 轻点被吃掉，拖动仍交给列表滚动。
                    Modifier.pointerInput(Unit) { detectTapGestures { } }
                },
            ),
    ) {
        CompositionLocalProvider(
            LocalPreferenceHighlighted provides highlighted,
            content = content,
        )
    }
}

/** 禁用项的透明度（0.38 是 Material 的 disabled 观感）。 */
private const val DISABLED_ITEM_ALPHA = 0.38f

@Composable
internal fun PreferenceItem(
    item: Preference.PreferenceItem<*, *>,
    highlightKey: String?,
) {
    val scope = rememberCoroutineScope()
    StatusWrapper(
        item = item,
        highlightKey = highlightKey,
    ) {
        when (item) {
            is Preference.PreferenceItem.SwitchPreference -> {
                val value by item.preference.collectAsState()
                SwitchPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    checked = value,
                    onCheckedChanged = { newValue ->
                        scope.launch {
                            if (item.onValueChanged(newValue)) {
                                item.preference.set(newValue)
                            }
                        }
                    },
                )
            }
            is Preference.PreferenceItem.SliderPreference -> {
                BaseSliderItem(
                    value = item.value,
                    valueRange = item.valueRange,
                    steps = item.steps,
                    title = item.title,
                    subtitle = item.subtitle,
                    valueString = item.valueString.takeUnless { it.isNullOrEmpty() } ?: item.value.toString(),
                    onChange = {
                        scope.launch {
                            item.onValueChanged(it)
                        }
                    },
                    titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = TitleFontSize),
                    modifier = Modifier.padding(
                        horizontal = PrefsHorizontalPadding,
                        vertical = PrefsVerticalPadding,
                    ),
                )
            }
            is Preference.PreferenceItem.ListPreference<*> -> {
                val value by item.preference.collectAsState()
                ListPreferenceWidget(
                    value = value,
                    title = item.title,
                    subtitle = item.internalSubtitleProvider(value, item.entries),
                    icon = item.icon,
                    entries = item.entries,
                    onValueChange = { newValue ->
                        scope.launch {
                            if (item.internalOnValueChanged(newValue!!)) {
                                item.internalSet(newValue)
                            }
                        }
                    },
                )
            }
            is Preference.PreferenceItem.BasicListPreference -> {
                ListPreferenceWidget(
                    value = item.value,
                    title = item.title,
                    subtitle = item.subtitleProvider(item.value, item.entries),
                    icon = item.icon,
                    entries = item.entries,
                    onValueChange = { scope.launch { item.onValueChanged(it) } },
                )
            }
            is Preference.PreferenceItem.MultiSelectListPreference<*> -> {
                val values by item.preference.collectAsState()
                MultiSelectListPreferenceWidget(
                    values = values,
                    title = item.title,
                    subtitle = item.internalSubtitleProvider(values, item.entries),
                    icon = item.icon,
                    entries = item.entries,
                    onValuesChange = { newValues ->
                        scope.launch {
                            if (item.internalOnValueChanged(newValues)) {
                                item.internalSet(newValues)
                            }
                        }
                    },
                )
            }
            is Preference.PreferenceItem.TextPreference -> {
                TextPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    widget = item.widget,
                    onPreferenceClick = item.onClick,
                )
            }
            is Preference.PreferenceItem.EditTextPreference -> {
                val values by item.preference.collectAsState()
                EditTextPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    value = values,
                    onConfirm = {
                        val accepted = item.onValueChanged(it)
                        if (accepted) item.preference.set(it)
                        accepted
                    },
                )
            }
            is Preference.PreferenceItem.TrackerPreference -> {
                val isLoggedIn by item.tracker.let { tracker ->
                    tracker.isLoggedInFlow.collectAsState(tracker.isLoggedIn)
                }
                TrackingPreferenceWidget(
                    tracker = item.tracker,
                    isLoggedIn = isLoggedIn,
                    onClick = { if (isLoggedIn) item.logout() else item.login() },
                )
            }
            is Preference.PreferenceItem.InfoPreference -> {
                InfoWidget(text = item.title)
            }
            is Preference.PreferenceItem.CustomPreference -> {
                item.content()
            }
        }
    }
}
