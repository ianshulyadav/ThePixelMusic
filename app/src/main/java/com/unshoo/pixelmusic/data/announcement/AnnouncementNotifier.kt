package com.unshoo.pixelmusic.data.announcement

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.unshoo.pixelmusic.MainActivity
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.github.AppAnnouncement
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Posts a system notification for a new announcement from the website admin page. */
@Singleton
class AnnouncementNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.announcements_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.announcements_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun show(announcement: AppAnnouncement) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.i("Announcement notification skipped: notifications not allowed")
            return
        }
        ensureChannel()

        // Tapping opens the app on the announcements inbox.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANNOUNCEMENTS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.pixelmusic_base_monochrome)
            .setColor(ContextCompat.getColor(context, R.color.my_primary))
            .setContentTitle(announcement.title)
            .setContentText(announcement.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(announcement.message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "pixelmusic_announcements"
        const val NOTIFICATION_ID = 2101
        const val ACTION_OPEN_ANNOUNCEMENTS = "com.unshoo.pixelmusic.action.OPEN_ANNOUNCEMENTS"
    }
}
