package com.unshoo.pixelmusic.presentation.components

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Applies native hardware window blur behind the Bottom Sheet on Android 12+ (API 31+).
 *
 * The flag and radius are always reversed in [DisposableEffect.onDispose]: without
 * teardown the blurred-behind flag leaked onto the host window after the sheet closed,
 * forcing the window compositor to keep running an unnecessary blur pass over the
 * whole app surface.
 */
@Composable
fun ApplyBottomSheetWindowBlur(isBlurActive: Boolean) {
    if (!isBlurActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view, isBlurActive) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: (view.context as? Activity)?.window
        try {
            window?.let { win ->
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                win.attributes = win.attributes.apply { blurBehindRadius = 50 }
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                window?.let { win ->
                    win.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    win.attributes = win.attributes.apply { blurBehindRadius = 0 }
                }
            } catch (_: Exception) {
            }
        }
    }
}
