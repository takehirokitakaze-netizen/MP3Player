package jp.kitakaze.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.C

/**
 * ホーム画面ウィジェット。
 * 上半分：曲名と再生位置（タップでアプリを開く）
 * 下半分：シークバー（20分割、タップした位置へジャンプ）
 */
open class PlayerWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_SEEK_TO = "jp.kitakaze.player.widget.SEEK_TO"
        private const val EXTRA_FRACTION = "fraction"
        private const val SEGMENTS = 20

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            update(context, manager, PlayerWidget::class.java, R.layout.widget_player)
            update(context, manager, PlayerWidgetSmall::class.java, R.layout.widget_player_small)
        }

        private fun update(context: Context, manager: AppWidgetManager, cls: Class<*>, layout: Int) {
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) return

            val prefs = Prefs.get(context)
            val service = PlaybackService.instance
            val player = service?.player
            val title = service?.currentTitle()
                ?: prefs.getString(Prefs.KEY_TITLE, null)
                ?: "（タップしてアプリでフォルダを選んでください）"
            val position = player?.currentPosition ?: prefs.getLong(Prefs.KEY_POSITION, 0L)
            val duration = player?.duration ?: C.TIME_UNSET
            val playing = player?.isPlaying == true

            val mark = if (playing) "▶" else "❚❚"
            val time = if (duration != C.TIME_UNSET && duration > 0) {
                "$mark  ${FloatingControls.formatTime(position)} / ${FloatingControls.formatTime(duration)}"
            } else {
                "$mark  ${FloatingControls.formatTime(position)}"
            }
            val progress = if (duration != C.TIME_UNSET && duration > 0) {
                (position * 1000 / duration).toInt().coerceIn(0, 1000)
            } else 0

            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_time, time)
            views.setProgressBar(R.id.widget_progress, 1000, progress, false)

            val openApp = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_top, openApp)

            for (i in 0 until SEGMENTS) {
                val id = context.resources.getIdentifier("seg$i", "id", context.packageName)
                if (id != 0) views.setOnClickPendingIntent(id, seekIntent(context, i))
            }

            manager.updateAppWidget(ids, views)
        }

        private fun seekIntent(context: Context, segment: Int): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java)
                .setAction(ACTION_SEEK_TO)
                .putExtra(EXTRA_FRACTION, segment.toFloat() / SEGMENTS)
            return PendingIntent.getBroadcast(
                context, 200 + segment, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SEEK_TO) return
        val service = PlaybackService.instance ?: return
        service.seekToFraction(intent.getFloatExtra(EXTRA_FRACTION, 0f))
        updateAll(context)
    }
}

/** 横4×縦1マスの小さいウィジェット（中身は同じ） */
class PlayerWidgetSmall : PlayerWidget()
