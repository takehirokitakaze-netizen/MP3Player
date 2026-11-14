package jp.kitakaze.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
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
    private lateinit var selectionBar: View
    private lateinit var deleteButton: Button
    private var selectionMode = false

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
        selectionBar = findViewById(R.id.selection_bar)
        deleteButton = findViewById(R.id.btn_sel_delete)

        findViewById<Button>(R.id.btn_folder).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            PlaybackService.instance?.exitApp()
            finish()
        }

        findViewById<Button>(R.id.btn_float).setOnClickListener { toggleFloating() }
        findViewById<Button>(R.id.btn_float_style).setOnClickListener { toggleFloatingStyle() }

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
            if (selectionMode) {
                updateSelectionCount()
            } else {
                controller?.run {
                    seekTo(position, 0L)
                    play()
                }
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (!selectionMode) enterSelection(position)
            true
        }
        findViewById<Button>(R.id.btn_sel_all).setOnClickListener { toggleSelectAll() }
        deleteButton.setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btn_sel_cancel).setOnClickListener { exitSelection() }

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

    override fun onResume() {
        super.onResume()
        // 「他のアプリの上に重ねて表示」の許可から戻ってきた時もここで反映
        updateFloatingButtons()
        PlaybackService.instance?.floating?.refresh()
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

        // 削除もできるように書き込み権限も受け取る（できなければ読み取りだけ）
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 以前のフォルダの権限は返しておく
        contentResolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach {
                var f = 0
                if (it.isReadPermission) f = f or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (it.isWritePermission) f = f or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try { contentResolver.releasePersistableUriPermission(it.uri, f) } catch (_: Exception) { }
            }

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

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    // ---- 選択モード（長押しで開始）と削除 ----

    private fun enterSelection(firstPosition: Int) {
        val tracks = shownTracks ?: return
        if (tracks.isEmpty()) return
        selectionMode = true
        listView.clearChoices()
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice, tracks.map { it.name }
        )
        listView.setItemChecked(firstPosition, true)
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
    }

    private fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        listView.clearChoices()
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        shownTracks = null // 一覧を作り直させる
        selectionBar.visibility = View.GONE
        refreshUi()
    }

    private fun updateSelectionCount() {
        deleteButton.text = "削除（${listView.checkedItemCount}）"
    }

    private fun toggleSelectAll() {
        val count = listView.adapter?.count ?: return
        val selectAll = listView.checkedItemCount < count
        for (i in 0 until count) listView.setItemChecked(i, selectAll)
        updateSelectionCount()
    }

    private fun hasWritePermission(): Boolean {
        val folder = Prefs.get(this).getString(Prefs.KEY_FOLDER, null) ?: return false
        return contentResolver.persistedUriPermissions.any {
            it.uri.toString() == folder && it.isWritePermission
        }
    }

    private fun confirmDelete() {
        val tracks = shownTracks ?: return
        val checked = listView.checkedItemPositions
        val targets = tracks.filterIndexed { i, _ -> checked.get(i) }
        if (targets.isEmpty()) {
            Toast.makeText(this, "ファイルが選ばれていません", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasWritePermission()) {
            AlertDialog.Builder(this)
                .setTitle("削除の許可が必要です")
                .setMessage("削除できるようにするため、同じフォルダをもう一度選んでください（最初の1回だけです）。")
                .setPositiveButton("フォルダを選ぶ") { _, _ ->
                    exitSelection()
                    pickFolder()
                }
                .setNegativeButton("やめる", null)
                .show()
            return
        }
        val preview = targets.take(5).joinToString("\n") { "・" + it.name } +
            if (targets.size > 5) "\n…ほか${targets.size - 5}件" else ""
        AlertDialog.Builder(this)
            .setTitle("${targets.size}件を削除しますか？")
            .setMessage("スマホから完全に削除されます。元に戻せません。\n\n$preview")
            .setPositiveButton("削除する") { _, _ -> deleteFiles(targets) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun deleteFiles(targets: List<Track>) {
        Thread {
            val deleted = mutableSetOf<String>()
            var failed = 0
            for (t in targets) {
                val ok = try {
                    DocumentsContract.deleteDocument(contentResolver, t.uri)
                } catch (e: Exception) {
                    false
                }
                if (ok) deleted += t.uri.toString() else failed++
            }
            runOnUiThread {
                PlaybackService.instance?.removeTracks(deleted)
                exitSelection()
                val msg = if (failed == 0) "${deleted.size}件削除しました"
                else "${deleted.size}件削除しました（${failed}件は削除できませんでした）"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectionMode) exitSelection() else super.onBackPressed()
    }

    private fun toggleFloating() {
        val prefs = Prefs.get(this)
        val on = !prefs.getBoolean(Prefs.KEY_FLOAT_ON, false)
        prefs.edit().putBoolean(Prefs.KEY_FLOAT_ON, on).apply()
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「MP3プレーヤー」をオンにして戻ってください", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else if (on && PlaybackService.instance?.player?.mediaItemCount == 0) {
            Toast.makeText(this, "フォルダを選ぶと浮きボタンが出ます", Toast.LENGTH_SHORT).show()
        }
        updateFloatingButtons()
        PlaybackService.instance?.floating?.refresh()
    }

    private fun toggleFloatingStyle() {
        val prefs = Prefs.get(this)
        val circle = prefs.getString(Prefs.KEY_FLOAT_STYLE, FloatingControls.STYLE_PANEL) == FloatingControls.STYLE_CIRCLE
        prefs.edit().putString(
            Prefs.KEY_FLOAT_STYLE,
            if (circle) FloatingControls.STYLE_PANEL else FloatingControls.STYLE_CIRCLE
        ).apply()
        PlaybackService.instance?.floating?.closeMenu()
        updateFloatingButtons()
    }

    private fun updateFloatingButtons() {
        val prefs = Prefs.get(this)
        val on = prefs.getBoolean(Prefs.KEY_FLOAT_ON, false)
        val circle = prefs.getString(Prefs.KEY_FLOAT_STYLE, FloatingControls.STYLE_PANEL) == FloatingControls.STYLE_CIRCLE
        findViewById<Button>(R.id.btn_float).text = if (on) "浮きボタン：ON" else "浮きボタン：OFF"
        findViewById<Button>(R.id.btn_float_style).text = if (circle) "形：サークル" else "形：パネル"
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

        if (!selectionMode) {
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
