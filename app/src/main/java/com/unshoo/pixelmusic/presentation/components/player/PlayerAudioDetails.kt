package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.utils.AudioMetaUtils

private val ProviderPrefix = Regex("(?i)\\bTido\\s*[•·]?\\s*")
private val BadgeShape = RoundedCornerShape(999.dp)

@Composable
internal fun rememberPlayerAudioDetails(
    songId: String,
    enabled: Boolean,
    mimeType: String?,
    bitrate: Int?,
    sampleRate: Int?,
    bitDepth: Int?,
    formatTag: String?,
): String? {
    if (!enabled) return null
    val label = remember(mimeType, bitrate, sampleRate, bitDepth, formatTag) {
        AudioMetaUtils.formatAudioMetaLabel(mimeType, bitrate, sampleRate, bitDepth, formatTag)
            ?.replace(ProviderPrefix, "")?.trim()?.trimStart('•', '·')?.trim()
            ?.takeIf(String::isNotBlank)
    }
    var lastKnownLabel by remember(songId) { mutableStateOf<String?>(null) }
    if (label != null && label != lastKnownLabel) SideEffect { lastKnownLabel = label }
    return label ?: lastKnownLabel ?: stringResource(R.string.cd_audio_format_icon)
}

@Composable
internal fun PlayerAudioDetailsBadge(
    label: String,
    color: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(BadgeShape).then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = BadgeShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color.copy(alpha = 0.96f),
    ) {
        val isDolbyAtmos = remember(label) {
            listOf("DOLBY", "ATMOS", "EAC3", "AC-4").any { label.contains(it, ignoreCase = true) }
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isDolbyAtmos) {
                Icon(Icons.Rounded.SurroundSound, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
