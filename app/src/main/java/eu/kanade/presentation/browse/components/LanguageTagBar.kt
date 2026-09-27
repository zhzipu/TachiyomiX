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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

@Composable
fun LanguageTagBar(
    languages: List<String>,
    onLanguageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    nsfwFilter: NsfwFilter? = null,
    onNsfwFilterClick: () -> Unit = {},
    onMoveLanguage: ((String, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dragThreshold = with(density) { 56.dp.toPx() }
    val currentLanguages by rememberUpdatedState(languages)
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
