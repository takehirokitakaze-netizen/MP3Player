package jp.kitakaze.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQ_FOLDER = 10
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 3.0f
    }

    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: Button
    private lateinit var speedView: TextView
    private lateinit var listView: ListView

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var shownTracks: List<Track>? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        timeView = findViewById(R.id.time)
        seekBar = findViewById(R.id.seek)
        playButton = findViewById(R.id.btn_play)
        speedView = findViewById(R.id.speed)
        listView = findViewById(R.id.list)

        findViewById<Button>(R.id.btn_folder).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            PlaybackService.instance?.exitApp()
            finish()
        }

        findViewById<Button>(R.id.btn_restart).setOnClickListener { controller?.seekTo(0) }
        findViewById<Button>(R.id.btn_back10).setOnClickListener { seekBy(-10_000) }
        playButton.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btn_fwd10).setOnClickListener { seekBy(10_000) }
        findViewById<Button>(R.id.btn_next).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.hasNextMediaItem()) c.seekToNextMediaItem()
            else Toast.makeText(this, "最後の曲です", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_speed_down).setOnClickListener { changeSpeed(-0.05f) }
        findViewById<Button>(R.id.btn_speed_up).setOnClickListener { changeSpeed(0.05f) }
        findViewById<Button>(R.id.btn_speed_reset).setOnClickListener { setSpeed(1.0f) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeView.text = "${fmt(progress.toLong())} / ${fmt(controller?.duration ?: 0)}"
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                controller?.seekTo(bar.progress.toLong())
                userSeeking = false
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            controller?.run {
                seekTo(position, 0L)
                play()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(playerListener)
            refreshUi()
        }, ContextCompat.getMainExecutor(this))
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        // 以前のフォルダの権限は返しておく
        contentResolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach { contentResolver.releasePersistableUriPermission(it.uri, flags) }

        Prefs.get(this).edit()
            .putString(Prefs.KEY_FOLDER, uri.toString())
            .remove(Prefs.KEY_CURRENT)
            .apply()

        Toast.makeText(this, "読み込み中…", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            val tracks = try { TrackScanner.scan(appContext, uri) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (tracks.isEmpty()) {
                    Toast.makeText(this, "音声ファイルが見つかりませんでした", Toast.LENGTH_LONG).show()
                } else {
                    PlaybackService.instance?.loadFolder(uri, tracks)
                    Toast.makeText(this, "${tracks.size}曲を読み込みました", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }.start()
    }

    // ---- 操作 ----

    private fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            Toast.makeText(this, "先にフォルダを選んでください", Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    private fun seekBy(ms: Long) {
        val c = controller ?: return
        PlaybackService.seekWithin(c, ms)
    }

    private fun changeSpeed(delta: Float) {
        val c = controller ?: return
        setSpeed(c.playbackParameters.speed + delta)
    }

    private fun setSpeed(value: Float) {
        val c = controller ?: return
        // 0.05刻みに丸める（浮動小数の誤差対策）
        val rounded = ((value * 20f).roundToInt() / 20f).coerceIn(MIN_SPEED, MAX_SPEED)
        c.setPlaybackSpeed(rounded)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "電池の最適化はすでに解除されています", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- 表示 ----

    private fun refreshUi() {
        val c = controller ?: return
        titleView.text = c.currentMediaItem?.mediaMetadata?.title ?: "「フォルダを選ぶ」から音声のあるフォルダを選んでください"
        playButton.text = if (c.isPlaying) "❚❚" else "▶"
        speedView.text = String.format(Locale.US, "%.2f倍", c.playbackParameters.speed)

        val tracks = PlaybackService.instance?.tracks ?: emptyList()
        if (tracks !== shownTracks) {
            shownTracks = tracks
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_activated_1, tracks.map { it.name }
            )
        }
        val index = c.currentMediaItemIndex
        if (index in tracks.indices) {
            listView.setItemChecked(index, true)
        }
        updatePosition()
    }

    private fun updatePosition() {
        val c = controller ?: return
        if (userSeeking) return
        val duration = c.duration
        val position = c.currentPosition
        if (duration != C.TIME_UNSET && duration > 0) {
            seekBar.max = duration.toInt()
            seekBar.progress = position.toInt()
        } else {
            seekBar.max = 1
            seekBar.progress = 0
        }
        timeView.text = "${fmt(position)} / ${fmt(duration)}"
    }

    private fun fmt(ms: Long): String {
        if (ms == C.TIME_UNSET || ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
