package com.unshoo.pixelmusic.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.presentation.model.SettingsCategory
import com.unshoo.pixelmusic.presentation.model.SettingsSearchIndex

/** One row in the Settings search results. */
data class SettingsSearchResult(
    val category: SettingsCategory,
    val title: String,
    val subtitle: String?,
    val categoryTitle: String,
    /** False for whole-category rows; true for an individual setting to scroll to and highlight. */
    val isSetting: Boolean = false,
)

/** Pill-shaped search field shown at the top of the Settings list. */
@Composable
fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.settings_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

/**
 * Matches [query] against every category and every indexed setting (localized title + subtitle).
 * All whitespace-separated words must match; title matches rank above subtitle-only matches.
 */
@Composable
fun rememberSettingsSearchResults(query: String, localModeEnabled: Boolean): List<SettingsSearchResult> {
    val context = LocalContext.current
    return remember(query, localModeEnabled, context) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return@remember emptyList()
        val res = context.resources
        fun matches(vararg texts: String?): Boolean {
            val hay = texts.filterNotNull().joinToString(" ").lowercase()
            return words.all { hay.contains(it) }
        }

        val categoryRows = SettingsCategory.entries.map { cat ->
            SettingsSearchResult(
                category = cat,
                title = res.getString(cat.titleRes),
                subtitle = res.getString(cat.subtitleRes),
                categoryTitle = res.getString(R.string.settings_top_bar_title),
            )
        }
        val settingRows = SettingsSearchIndex.entries.map { entry ->
            SettingsSearchResult(
                category = entry.category,
                title = res.getString(entry.titleRes),
                subtitle = entry.subtitleRes?.let(res::getString),
                categoryTitle = res.getString(entry.category.titleRes),
                isSetting = true,
            )
        }
        (categoryRows + settingRows)
            .filter { !localModeEnabled || it.category != SettingsCategory.LASTFM }
            .filter { matches(it.title, it.subtitle, it.categoryTitle) }
            .distinctBy { it.category to it.title }
            .sortedBy { if (matches(it.title)) 0 else 1 }
            .take(50)
    }
}

/** Results list shown in place of the category list while a query is active. */
@Composable
fun SettingsSearchResults(
    results: List<SettingsSearchResult>,
    onResultClick: (SettingsSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_no_results),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 8.dp)
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        results.forEachIndexed { index, result ->
            val shape = when {
                results.size == 1 -> RoundedCornerShape(24.dp)
                index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                index == results.lastIndex -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                else -> RoundedCornerShape(4.dp)
            }
            Surface(
                onClick = { onResultClick(result) },
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!result.subtitle.isNullOrBlank()) {
                            Text(
                                text = result.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = result.categoryTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
