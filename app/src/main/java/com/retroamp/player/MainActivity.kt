
package com.retroamp.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(49, 49, 53)
    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)
    private val tracks = mutableListOf<Pair<String, Uri>>()
    private var index = -1
    private var player: MediaPlayer? = null
    private var shuffle = false
    private var repeat = false
    private var seeking = false
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var playlist: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                if (!seeking) seek.progress = p.currentPosition.coerceAtMost(seek.max)
                time.text = "${fmt(p.currentPosition)} / ${fmt(p.duration.coerceAtLeast(0))}"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(25,25,28)); setPadding(dp(12), dp(18), dp(12), dp(12)) }
        setContentView(root)
        val header = label("▣ RETROAMP   •   CLASSIC PLAYER", silver, 17f).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(dp(10),dp(12),dp(6),dp(12)); setBackgroundColor(Color.rgb(65,65,73)) }
        root.addView(header, full())
        val display = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(15),dp(12),dp(15)); setBackgroundColor(Color.rgb(5,22,13)) }
        root.addView(display, full())
        title = label("NO TRACK LOADED", green, 16f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; isSelected = true; typeface = Typeface.MONOSPACE }
        display.addView(title)
        time = label("00:00 / 00:00     128 KBPS     STEREO", green, 14f).apply { typeface = Typeface.MONOSPACE }
        display.addView(time)
        val eq = label("▁▃▆▂▇▄▂▅▇▃▁▆▃▇▂▅▃▁▆▇▂▃▆▁", green, 22f).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        display.addView(eq, full())
        seek = SeekBar(this).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(green); thumbTintList = android.content.res.ColorStateList.valueOf(green) }
        root.addView(seek, full())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) { player?.seekTo(seek.progress); seeking = false }
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {}
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(controls, full())
        fun control(symbol: String, action: () -> Unit): Button = Button(this).apply {
            text = symbol; textSize = 18f; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(75,75,84)))
            controls.addView(this, LinearLayout.LayoutParams(0, dp(54), 1f)); setOnClickListener { action() }
        }
        control("|◀") { move(-1) }
        playButton = control("▶") { toggle() }
        control("■") { stop() }
        control("▶|") { move(1) }
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(options, full())
        val add = Button(this).apply { text = "+ ADD MP3"; isAllCaps = false; setTextColor(green); setOnClickListener { pickAudio() } }
        options.addView(add, LinearLayout.LayoutParams(0,dp(55),2f))
        val random = Button(this).apply { text = "SHUFFLE OFF"; textSize = 11f; isAllCaps = false; setOnClickListener { shuffle = !shuffle; text = if (shuffle) "SHUFFLE ON" else "SHUFFLE OFF" } }
        options.addView(random, LinearLayout.LayoutParams(0,dp(55),1.5f))
        val loop = Button(this).apply { text = "REPEAT OFF"; textSize = 11f; isAllCaps = false; setOnClickListener { repeat = !repeat; text = if (repeat) "REPEAT ON" else "REPEAT OFF" } }
        options.addView(loop, LinearLayout.LayoutParams(0,dp(55),1.5f))
        root.addView(label("  PLAYLIST EDITOR", silver, 14f).apply { setBackgroundColor(Color.rgb(65,65,73)); setPadding(4,12,4,12) }, full())
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(11,19,13)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        playlist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(playlist)
        refreshPlaylist()
        root.addView(label("RETROAMP 1.0  •  LOCAL AUDIO", silver, 11f).apply { gravity = Gravity.CENTER }, full())
        handler.post(ticker)
    }
    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        startActivityForResult(intent, 100)
    }
    @Deprecated("Legacy activity result used for simple standalone sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
        data.data?.let { if (!uris.contains(it)) uris.add(it) }
        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = queryName(uri)
            if (tracks.none { it.second == uri }) tracks.add(name to uri)
        }
        refreshPlaylist()
        if (index == -1 && tracks.isNotEmpty()) playAt(0)
    }
    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Unknown track"
        }
        return uri.lastPathSegment ?: "Unknown track"
    }
    private fun refreshPlaylist() {
        playlist.removeAllViews()
        if (tracks.isEmpty()) playlist.addView(label("  Playlist empty. Tap + ADD MP3", green, 14f))
        tracks.forEachIndexed { i, track ->
            val row = label("  ${i+1}. ${track.first}", if (i == index) Color.WHITE else green, 14f).apply {
                setPadding(dp(7),dp(11),dp(7),dp(11)); typeface = Typeface.MONOSPACE
                if (i == index) setBackgroundColor(Color.rgb(47,83,55))
                setOnClickListener { playAt(i) }
            }
            playlist.addView(row, full())
        }
    }
    private fun playAt(i: Int) {
        if (i !in tracks.indices) return
        player?.release(); player = null; index = i
        try {
            val p = MediaPlayer()
            p.setDataSource(this, tracks[i].second)
            p.setOnPreparedListener { seek.max = it.duration.coerceAtLeast(1); it.start(); playButton.text = "Ⅱ" }
            p.setOnCompletionListener { if (repeat) playAt(index) else move(1) }
            p.setOnErrorListener { _, _, _ -> Toast.makeText(this,"Unable to play file",Toast.LENGTH_SHORT).show(); true }
            player = p
            p.prepareAsync()
            title.text = tracks[i].first.uppercase(Locale.getDefault())
            refreshPlaylist()
        } catch (e: Exception) { Toast.makeText(this,"Cannot open audio: ${e.message}",Toast.LENGTH_LONG).show() }
    }
    private fun toggle() {
        val p = player ?: run { if (tracks.isNotEmpty()) playAt(0) else pickAudio(); return }
        if (p.isPlaying) { p.pause(); playButton.text = "▶" } else { p.start(); playButton.text = "Ⅱ" }
    }
    private fun stop() { player?.pause(); player?.seekTo(0); playButton.text = "▶" }
    private fun move(delta: Int) {
        if (tracks.isEmpty()) return
        val next = if (shuffle && tracks.size > 1) (tracks.indices.filter { it != index }).random() else index + delta
        if (next in tracks.indices) playAt(next) else { stop(); if (delta > 0) { index = -1; refreshPlaylist() } }
    }
    private fun fmt(ms: Int) = "%02d:%02d".format(Locale.US,ms/60000,(ms/1000)%60)
    private fun label(s: String, color: Int, size: Float) = TextView(this).apply { text=s; setTextColor(color); textSize=size; setPadding(dp(4),dp(7),dp(4),dp(7)) }
    private fun full() = LinearLayout.LayoutParams(-1,-2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { handler.removeCallbacks(ticker); player?.release(); super.onDestroy() }
}
