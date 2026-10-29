package eu.kanade.presentation.browse.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.presentation.core.components.material.padding

enum class NsfwFilter {
    ShowAll,
    OnlyNsfw,
    HideNsfw,
}

/**
 * 「文」标签的勾选状态，决定图源/插件列表的排列方式：
 *  - 勾选（默认）＝按语言分组排列
 *  - 取消勾选＝按名称首字母（汉字取拼音首字母）分组排列，同名条目只出现一次
 *
 * 图源列表与插件列表共用同一份状态，切换 tab 后保持一致；只在进程内有效，
 * 重启应用回到默认的按语言分组（与 18+ 过滤一样不落盘）。
 */
object LanguageGroupingPreference {
    var enabled by mutableStateOf(true)

    fun toggle() {
        enabled = !enabled
    }

    /** 点到语言标签时回到按语言分组（把「文」重新勾上） */
    fun check() {
        enabled = true
    }
}

@Composable
fun LanguageTagBar(
    languages: List<String>,
    onLanguageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    nsfwFilter: NsfwFilter? = null,
    onNsfwFilterClick: () -> Unit = {},
    onMoveLanguage: ((String, Int) -> Unit)? = null,
    // SY -->
    groupByLanguageEnabled: Boolean? = null,
    onGroupByLanguageClick: () -> Unit = {},
    // SY <--
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dragThreshold = with(density) { 56.dp.toPx() }
    val currentLanguages by rememberUpdatedState(languages)
    // SY --> 「文」取消勾选（按首字母排列）时，列表已不再按语言分组，语言标签没有意义，全部隐藏
    val showLanguageTags = groupByLanguageEnabled != false
    Column(modifier = modifier) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FilterChipDefaults.Height + MaterialTheme.padding.small * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                // SY --> 只在「文」勾选时显示语言标签
                if (showLanguageTags) {
                    items(items = languages, key = { it }) { lang ->
                        var dragOffset by remember(lang) { mutableFloatStateOf(0f) }
                        val dragModifier = if (onMoveLanguage != null) {
                            Modifier
                                .graphicsLayer { translationX = dragOffset }
                                .pointerInput(lang) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragOffset = 0f },
                                        onDragEnd = { dragOffset = 0f },
                                        onDragCancel = { dragOffset = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.x
                                            while (dragOffset > dragThreshold) {
                                                val currentIndex = currentLanguages.indexOf(lang)
                                                if (currentIndex < currentLanguages.lastIndex) {
                                                    onMoveLanguage(lang, currentIndex + 1)
                                                }
                                                dragOffset -= dragThreshold
                                            }
                                            while (dragOffset < -dragThreshold) {
                                                val currentIndex = currentLanguages.indexOf(lang)
                                                if (currentIndex > 0) {
                                                    onMoveLanguage(lang, currentIndex - 1)
                                                }
                                                dragOffset += dragThreshold
                                            }
                                        },
                                    )
                                }
                        } else {
                            Modifier
                        }
                        LanguageTagChip(
                            label = LocaleHelper.getSourceDisplayName(lang, context),
                            onClick = { onLanguageClick(lang) },
                            modifier = Modifier
                                .animateItem()
                                .then(dragModifier),
                        )
                    }
                }
                // SY <--
            }
            if (groupByLanguageEnabled != null) {
                LanguageGroupingChip(
                    enabled = groupByLanguageEnabled,
                    onClick = onGroupByLanguageClick,
                    modifier = Modifier.padding(end = MaterialTheme.padding.small),
                )
            }
            if (nsfwFilter != null) {
                NsfwFilterChip(
                    filter = nsfwFilter,
                    onClick = onNsfwFilterClick,
                    modifier = Modifier.padding(end = MaterialTheme.padding.small),
                )
            }
        }
        HorizontalDivider()
    }
}

/**
 * 「文」标签：外观与 18+ 标签一致（文字 + 复选框）。
 * 勾选＝按语言分组排列，取消勾选＝按首字母排列。
 */
@Composable
private fun LanguageGroupingChip(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FilterChipDefaults.filterChipColors()
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = FilterChipDefaults.shape,
        color = colors.containerColor,
        contentColor = colors.labelColor,
        border = BorderStroke(
            width = if (enabled) 2.dp else 1.dp,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(FilterChipDefaults.Height)
                .padding(horizontal = 12.dp),
        ) {
            Text(text = "文", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = enabled,
                onCheckedChange = { onClick() },
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        }
    }
}

@Composable
private fun NsfwFilterChip(
    filter: NsfwFilter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FilterChipDefaults.filterChipColors()
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = FilterChipDefaults.shape,
        color = colors.containerColor,
        contentColor = colors.labelColor,
        border = BorderStroke(
            width = if (filter != NsfwFilter.ShowAll) 2.dp else 1.dp,
            color = if (filter != NsfwFilter.ShowAll) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(FilterChipDefaults.Height)
                .padding(horizontal = 12.dp),
        ) {
            Text(text = "18+", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            TriStateCheckbox(
                state = when (filter) {
                    NsfwFilter.OnlyNsfw -> ToggleableState.On
                    NsfwFilter.HideNsfw -> ToggleableState.Off
                    NsfwFilter.ShowAll -> ToggleableState.Indeterminate
                },
                onClick = { onClick() },
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        }
    }
}

@Composable
private fun LanguageTagChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FilterChipDefaults.filterChipColors()
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = FilterChipDefaults.shape,
        color = colors.containerColor,
        contentColor = colors.labelColor,
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(FilterChipDefaults.Height)
                .padding(horizontal = 16.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
