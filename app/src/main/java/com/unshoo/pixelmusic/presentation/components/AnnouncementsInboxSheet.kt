package com.unshoo.pixelmusic.presentation.components

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.github.AppAnnouncement
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Full-screen "What's new" page (like a music app's notifications screen): every announcement
 * published from the website admin page, newest first, split into New (unread) and Earlier.
 * Tapping one opens it full screen.
 *
 * @param readAt publish time of the newest announcement read before this page was opened;
 *   anything newer is shown as unread.
 */
@Composable
fun AnnouncementsInboxSheet(
    announcements: List<AppAnnouncement>,
    readAt: Long,
    onDismiss: () -> Unit,
) {
    var opened by remember { mutableStateOf<AppAnnouncement?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                // Header
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.birthday_back))
                    }
                }
                Text(
                    text = stringResource(R.string.announcements_title),
                    fontFamily = GoogleSansRounded,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
                Text(
                    text = stringResource(R.string.announcements_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp),
                )

                if (announcements.isEmpty()) {
                    EmptyState()
                } else {
                    val newOnes = announcements.filter { it.publishedAt > readAt }
                    val earlier = announcements.filter { it.publishedAt <= readAt }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (newOnes.isNotEmpty()) {
                            item(key = "h-new") { SectionHeader(stringResource(R.string.announcements_section_new)) }
                            items(newOnes, key = { it.id }) { a -> FeedItem(a, unread = true) { opened = a } }
                        }
                        if (earlier.isNotEmpty()) {
                            item(key = "h-earlier") {
                                SectionHeader(
                                    stringResource(
                                        if (newOnes.isEmpty()) R.string.announcements_section_all
                                        else R.string.announcements_section_earlier,
                                    ),
                                )
                            }
                            items(earlier, key = { it.id }) { a -> FeedItem(a, unread = false) { opened = a } }
                        }
                    }
                }
            }
        }
    }

    opened?.let { announcement ->
        AppAnnouncementDialog(announcement = announcement, onDismiss = { opened = null })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FeedItem(announcement: AppAnnouncement, unread: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AbsoluteSmoothCornerShape(24.dp, 60),
        color = if (unread) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val content = if (unread) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = AbsoluteSmoothCornerShape(16.dp, 60),
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Campaign,
                        contentDescription = null,
                        tint = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = announcement.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (announcement.message.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = announcement.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (announcement.publishedAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            announcement.publishedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = content.copy(alpha = 0.6f),
                    )
                }
            }
            if (unread) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(10.dp)
                        .clickable(enabled = false) {}
                        .then(Modifier),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxSize()) {}
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = AbsoluteSmoothCornerShape(36.dp, 60),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(104.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.NotificationsNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.announcements_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.announcements_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
