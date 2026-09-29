package com.hpre.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.hpre.app.R
import com.hpre.app.player.HPrePlaybackService

/** Home-screen widget mirroring the playback session: title + play/pause + prev/next. */
class HPreWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        pushUpdate(context, appWidgetManager, appWidgetIds)
        ensureController(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (!action.startsWith(ACTION_PREFIX)) return
        val ctrl = controller ?: run {
            ensureController(context)
            return
        }
        when (action) {
            ACTION_PLAY_PAUSE -> if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
            ACTION_NEXT -> ctrl.seekToNext()
            ACTION_PREV -> ctrl.seekToPrevious()
        }
        pushUpdateAll(context)
    }

    private fun pushUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    private fun pushUpdateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, HPreWidgetProvider::class.java)
        )
        pushUpdate(context, manager, ids)
    }

    private fun ensureController(context: Context) {
        if (controller != null || pendingController != null) return
        val appContext = context.applicationContext
        val token = SessionToken(
            appContext,
            ComponentName(appContext, HPrePlaybackService::class.java)
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        pendingController = future
        future.addListener({
            pendingController = null
            try {
                controller = future.get().also { c ->
                    c.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (events.containsAny(
                                    Player.EVENT_IS_PLAYING_CHANGED,
                                    Player.EVENT_MEDIA_METADATA_CHANGED,
                                    Player.EVENT_PLAYBACK_STATE_CHANGED
                                )
                            ) {
                                pushUpdateAll(appContext)
                            }
                        }
                    })
                }
            } catch (_: Throwable) {
                controller = null
            }
            pushUpdateAll(appContext)
        }, MoreExecutors.directExecutor())
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_player)
        val ctrl = controller
        val title = ctrl?.mediaMetadata?.title?.toString()
        views.setTextViewText(
            R.id.widget_title,
            title ?: context.getString(R.string.widget_idle_title)
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (ctrl?.isPlaying == true) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        )
        views.setOnClickPendingIntent(R.id.widget_play_pause, actionPendingIntent(context, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.widget_prev, actionPendingIntent(context, ACTION_PREV))
        return views
    }

    private fun actionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, HPreWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val ACTION_PREFIX = "com.hpre.app.widget."
        private const val ACTION_PLAY_PAUSE = "${ACTION_PREFIX}PLAY_PAUSE"
        private const val ACTION_NEXT = "${ACTION_PREFIX}NEXT"
        private const val ACTION_PREV = "${ACTION_PREFIX}PREV"

        private var controller: MediaController? = null
        private var pendingController: ListenableFuture<MediaController>? = null
    }
}
