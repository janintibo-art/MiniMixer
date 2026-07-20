package com.minimixer.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.RemoteViews

class MixerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACT_UP = "com.minimixer.app.VOL_UP"
        const val ACT_DOWN = "com.minimixer.app.VOL_DOWN"

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MixerWidgetProvider::class.java))
            ids.forEach { update(context, mgr, it) }
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val views = RemoteViews(context.packageName, R.layout.widget_mixer)

            views.setProgressBar(
                R.id.widgetVol,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                false
            )

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetLabel, openApp)

            fun action(act: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
                context, req,
                Intent(context, MixerWidgetProvider::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnVolDown, action(ACT_DOWN, 1))
            views.setOnClickPendingIntent(R.id.btnVolUp, action(ACT_UP, 2))

            mgr.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (intent.action) {
            ACT_UP -> runCatching {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            ACT_DOWN -> runCatching {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            else -> return
        }
        refreshAll(context)
    }
}
