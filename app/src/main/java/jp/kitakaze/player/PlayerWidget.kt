package jp.kitakaze.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat

/** ホーム画面ウィジェット：曲名・再生/一時停止・-30/-10/+10/+30秒・+1分 */
open class PlayerWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_PLAY_PAUSE = "jp.kitakaze.player.widget.PLAY_PAUSE"
        private const val ACTION_SEEK = "jp.kitakaze.player.widget.SEEK"
        private const val EXTRA_MS = "ms"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            update(context, manager, PlayerWidget::class.java, R.layout.widget_player)
            update(context, manager, PlayerWidgetSmall::class.java, R.layout.widget_player_small)
        }

        private fun update(context: Context, manager: AppWidgetManager, cls: Class<*>, layout: Int) {
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) return

            val service = PlaybackService.instance
            val title = service?.currentTitle()
                ?: Prefs.get(context).getString(Prefs.KEY_TITLE, null)
                ?: "（アプリでフォルダを選んでください）"
            val playing = service?.player?.isPlaying == true

            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.w_play, if (playing) "❚❚" else "▶")

            val openApp = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_title, openApp)
            views.setOnClickPendingIntent(R.id.w_play, action(context, ACTION_PLAY_PAUSE, 0, 1))
            views.setOnClickPendingIntent(R.id.w_back30, action(context, ACTION_SEEK, -30_000, 2))
            views.setOnClickPendingIntent(R.id.w_back10, action(context, ACTION_SEEK, -10_000, 3))
            views.setOnClickPendingIntent(R.id.w_fwd10, action(context, ACTION_SEEK, 10_000, 4))
            views.setOnClickPendingIntent(R.id.w_fwd30, action(context, ACTION_SEEK, 30_000, 5))
            views.setOnClickPendingIntent(R.id.w_fwd60, action(context, ACTION_SEEK, 60_000, 6))

            manager.updateAppWidget(ids, views)
        }

        private fun action(context: Context, action: String, ms: Long, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java)
                .setAction(action)
                .putExtra(EXTRA_MS, ms)
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PLAY_PAUSE && action != ACTION_SEEK) return

        val service = PlaybackService.instance
        if (service != null && service.player.mediaItemCount > 0) {
            when (action) {
                ACTION_PLAY_PAUSE -> service.togglePlay()
                ACTION_SEEK -> service.seekBy(intent.getLongExtra(EXTRA_MS, 0))
            }
            updateAll(context)
        } else if (action == ACTION_PLAY_PAUSE) {
            // アプリが終了している場合：サービスを起こして前回の続きから再生
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_WIDGET_PLAY)
                )
            } catch (e: Exception) {
                Toast.makeText(context, "アプリを開いて再生してください", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** 横4×縦1マスの小さいウィジェット（ボタン操作は PlayerWidget 側で処理） */
class PlayerWidgetSmall : PlayerWidget()
