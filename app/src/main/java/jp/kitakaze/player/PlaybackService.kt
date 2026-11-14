package jp.kitakaze.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 再生の本体。画面を消しても・アプリを閉じても再生を続けるサービス。
 * 一時停止中もフォアグラウンドのまま残すことで、システムに止められにくくしている。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        var instance: PlaybackService? = null
            private set

        const val ACTION_WIDGET_PLAY = "jp.kitakaze.player.action.WIDGET_PLAY"
        private const val NOTIFICATION_ID = 1001 // Media3の通知と同じID
        private const val CHANNEL_ID = "playback_start"

        /** 現在位置から ms だけ移動（曲の範囲内に収める） */
        fun seekWithin(player: Player, ms: Long) {
            var target = player.currentPosition + ms
            val duration = player.duration
            if (duration != C.TIME_UNSET && target > duration) target = (duration - 500).coerceAtLeast(0)
            if (target < 0) target = 0
            player.seekTo(target)
        }
    }

    private var session: MediaSession? = null
    lateinit var player: ExoPlayer
        private set
    var tracks: List<Track> = emptyList()
        private set
    var floating: FloatingControls? = null
        private set
    private var exiting = false
    private val handler = Handler(Looper.getMainLooper())

    private val periodicSave = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, 10_000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(periodicSave)
            if (isPlaying) handler.postDelayed(periodicSave, 10_000) else saveState()
            PlayerWidget.updateAll(this@PlaybackService)
            floating?.onStateChanged()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveState()
            PlayerWidget.updateAll(this@PlaybackService)
            floating?.onStateChanged()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            Prefs.get(this@PlaybackService).edit()
                .putFloat(Prefs.KEY_SPEED, playbackParameters.speed).apply()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 読めないファイルは飛ばして次へ
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true // 電話中は止まり、終われば再開。ナビ音声中は音量を下げる
            )
            .setHandleAudioBecomingNoisy(true) // イヤホンが外れたら一時停止
            .setWakeMode(C.WAKE_MODE_LOCAL)     // 画面オフ中もCPUを止めさせない
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()

        restoreState()
        PlayerWidget.updateAll(this)
        floating = FloatingControls(this).also { it.refresh() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 曲が読み込まれている間は、一時停止中もフォアグラウンドを維持する */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keep = !exiting && player.mediaItemCount > 0
        super.onUpdateNotification(session, startInForegroundRequired || keep)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_PLAY) {
            // アプリが完全に終了していた時に、ウィジェットの再生ボタンから起動された場合
            startForegroundPlaceholder()
            if (player.mediaItemCount == 0) restoreState()
            if (player.mediaItemCount > 0) {
                player.prepare()
                player.play()
            } else {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 履歴からアプリを消しても再生は止めない（終了はアプリ内の「終了」ボタンで）
        saveState()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        floating?.hideAll()
        floating = null
        saveState()
        session?.run {
            player.release()
            release()
        }
        session = null
        instance = null
        PlayerWidget.updateAll(this)
        super.onDestroy()
    }

    // ---- 操作 ----

    fun loadFolder(folder: Uri, newTracks: List<Track>) {
        tracks = newTracks
        Prefs.get(this).edit().putString(Prefs.KEY_FOLDER, folder.toString()).apply()
        player.setMediaItems(newTracks.map { it.toMediaItem() }, 0, 0L)
        player.prepare()
        saveState()
        PlayerWidget.updateAll(this)
        floating?.refresh()
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.mediaItemCount == 0) return
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun seekBy(ms: Long) = seekWithin(player, ms)

    fun currentTitle(): String? = player.currentMediaItem?.mediaMetadata?.title?.toString()

    fun exitApp() {
        saveState()
        floating?.hideAll()
        exiting = true
        player.pause()
        player.stop()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
        PlayerWidget.updateAll(this)
    }

    // ---- 状態の保存・復元 ----

    private fun saveState() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        Prefs.get(this).edit()
            .putString(Prefs.KEY_CURRENT, item.mediaId)
            .putString(Prefs.KEY_TITLE, item.mediaMetadata.title?.toString())
            .putLong(Prefs.KEY_POSITION, player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    private fun restoreState() {
        val prefs = Prefs.get(this)
        player.setPlaybackSpeed(prefs.getFloat(Prefs.KEY_SPEED, 1f))
        val folder = prefs.getString(Prefs.KEY_FOLDER, null) ?: return
        val found = try {
            TrackScanner.scan(this, Uri.parse(folder))
        } catch (e: Exception) {
            emptyList()
        }
        if (found.isEmpty()) return
        tracks = found
        val current = prefs.getString(Prefs.KEY_CURRENT, null)
        val index = found.indexOfFirst { it.uri.toString() == current }
        val position = if (index >= 0) prefs.getLong(Prefs.KEY_POSITION, 0L) else 0L
        player.setMediaItems(found.map { it.toMediaItem() }, index.coerceAtLeast(0), position)
        player.prepare()
    }

    private fun startForegroundPlaceholder() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "再生の開始", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("再生を準備しています")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
