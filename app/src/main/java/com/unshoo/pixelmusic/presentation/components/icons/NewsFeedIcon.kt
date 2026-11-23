package com.unshoo.pixelmusic.presentation.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * "News" page icon (torn zig-zag top edge, header bar, picture box + text lines) used for the
 * announcements / notifications inbox on the home top bar. Even-odd fill so the inner bars are
 * cut out of the page and the picture box sits inside its own cut-out frame.
 */
val NewsFeedIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NewsFeed",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Page with a zig-zag torn top edge and rounded bottom corners.
            moveTo(3f, 5f)
            lineTo(5f, 3f); lineTo(7f, 5f); lineTo(9f, 3f); lineTo(11f, 5f)
            lineTo(13f, 3f); lineTo(15f, 5f); lineTo(17f, 3f); lineTo(19f, 5f); lineTo(21f, 3f)
            verticalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 19f, 21f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 19f)
            close()
            // Header bar (cut out).
            moveTo(5.5f, 8.5f); horizontalLineTo(18.5f); verticalLineTo(10.2f); horizontalLineTo(5.5f); close()
            // Picture frame (cut out) ...
            moveTo(5.5f, 12f); horizontalLineTo(11f); verticalLineTo(18.5f); horizontalLineTo(5.5f); close()
            // ... with the picture itself filled back in.
            moveTo(7f, 13.5f); horizontalLineTo(9.5f); verticalLineTo(17f); horizontalLineTo(7f); close()
            // Text lines (cut out).
            moveTo(12.5f, 12f); horizontalLineTo(18.5f); verticalLineTo(13.7f); horizontalLineTo(12.5f); close()
            moveTo(12.5f, 16.8f); horizontalLineTo(18.5f); verticalLineTo(18.5f); horizontalLineTo(12.5f); close()
        }
    }.build()
}
