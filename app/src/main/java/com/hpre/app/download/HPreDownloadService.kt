package com.hpre.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import com.hpre.app.HPreApplication
import com.hpre.app.R

/** Foreground service driving Media3 [DownloadManager]; lives only while downloads are active. */
@UnstableApi
class HPreDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadTracker.NOTIFICATION_CHANNEL_ID,
    R.string.download_channel_name,
    0
) {

    override fun getDownloadManager(): DownloadManager {
        val app = application as HPreApplication
        return requireNotNull(app.container.downloadTracker).downloadManager
    }

    override fun getScheduler(): androidx.media3.exoplayer.scheduler.Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        return DownloadNotificationHelper(this, DownloadTracker.NOTIFICATION_CHANNEL_ID)
            .buildProgressNotification(
                this,
                R.drawable.ic_widget_play,
                null,
                null,
                downloads,
                notMetRequirements
            )
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(DownloadTracker.NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DownloadTracker.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 9001
    }
}
