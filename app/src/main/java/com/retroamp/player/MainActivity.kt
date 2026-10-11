
package com.retroamp.player

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)

    private val tracks = mutableListOf<Pair<String, Uri>>()
    private var index = -1
    private var player: MediaPlayer? = null
    private var shuffle = false
    private var repeat = false
    private var seeking = false
    private var prepared = false

    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var playlist: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && prepared) {
                try {
                    if (!seeking) {
                        seek.progress = p.currentPosition.coerceAtMost(seek.max)
                    }
                    time.text = "${fmt(p.currentPosition)} / ${fmt(p.duration.coerceAtLeast(0))}"
                } catch (_: IllegalStateException) {}
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(25, 25, 28))
            setPadding(dp(12), dp(18), dp(12), dp(12))
        }
        setContentView(root)

        val header = label(
            "▣ RETROAMP   •   CLASSIC PLAYER",
            silver, 17f
        ).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(12), dp(6), dp(12))
            setBackgroundColor(Color.rgb(65, 65, 73))
        }
        root.addView(header, full())

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(15), dp(12), dp(15))
            setBackgroundColor(Color.rgb(5, 22, 13))
        }
        root.addView(display, full())

        title = label("NO TRACK LOADED", green, 16f).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSelected = true
            typeface = Typeface.MONOSPACE
        }
        display.addView(title)

        time = label("00:00 / 00:00", green, 14f).apply {
            typeface = Typeface.MONOSPACE
        }
        display.addView(time)

        // EQUALIZADOR ANIMADO SIMPLES
        val equalizer = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
            }

            private val bars = FloatArray(24) { 0.08f }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                val gap = dp(3).toFloat()
                val barWidth = ((width - gap * 23) / 24f).coerceAtLeast(1f)
                val maxHeight = height * 0.85f

                val playing = try {
                    prepared && player?.isPlaying == true
                } catch (_: IllegalStateException) {
                    false
                }

                for (i in bars.indices) {
                    val target = if (playing) {
                        Random.nextFloat() * 0.85f + 0.15f
                    } else {
                        0.08f
                    }

                    bars[i] += (target - bars[i]) * 0.45f

                    val left = i * (barWidth + gap)
                    val top = height - bars[i] * maxHeight

                    canvas.drawRect(
                        left,
                        top,
                        left + barWidth,
                        height.toFloat(),
                        paint
                    )
                }

                postInvalidateDelayed(90)
            }
        }

        display.addView(
            equalizer,
            LinearLayout.LayoutParams(-1, dp(90))
        )

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(green)
        }
        root.addView(seek, full())

        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (prepared) {
                        player?.seekTo(seek.progress)
                    }
                    seeking = false
                }

                override fun onProgressChanged(
                    s: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {}
            }
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(controls, full())

        fun control(symbol: String, action: () -> Unit): Button =
            Button(this).apply {
                text = symbol
                textSize = 18f
                setTextColor(Color.WHITE)
                isAllCaps = false
                backgroundTintList = ColorStateList.valueOf(
                    Color.rgb(75, 75, 84)
                )
                controls.addView(
                    this,
                    LinearLayout.LayoutParams(0, dp(54), 1f)
                )
                setOnClickListener { action() }
            }

        control("|◀") { move(-1) }
        playButton = control("▶") { toggle() }
        control("■") { stop() }
        control("▶|") { move(1) }

        val options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(options, full())

        val add = Button(this).apply {
            text = "+ ADD MP3"
            isAllCaps = false
            setTextColor(green)
            setOnClickListener { pickAudio() }
        }
        options.addView(
            add,
            LinearLayout.LayoutParams(0, dp(55), 2f)
        )

        val random = Button(this).apply {
            text = "SHUFFLE OFF"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                shuffle = !shuffle
                text = if (shuffle) "SHUFFLE ON" else "SHUFFLE OFF"
            }
        }
        options.addView(
            random,
            LinearLayout.LayoutParams(0, dp(55), 1.5f)
        )

        val loop = Button(this).apply {
            text = "REPEAT OFF"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                repeat = !repeat
                text = if (repeat) "REPEAT ON" else "REPEAT OFF"
            }
        }
        options.addView(
            loop,
            LinearLayout.LayoutParams(0, dp(55), 1.5f)
        )

        root.addView(
            label("  PLAYLIST EDITOR", silver, 14f).apply {
                setBackgroundColor(Color.rgb(65, 65, 73))
                setPadding(dp(4), dp(12), dp(4), dp(12))
            },
            full()
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 19, 13))
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        playlist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(playlist)

        refreshPlaylist()

        root.addView(
            label("RETROAMP 1.1  •  LOCAL AUDIO", silver, 11f).apply {
                gravity = Gravity.CENTER
            },
            full()
        )

        handler.post(ticker)
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 100)
    }

    @Deprecated("Legacy activity result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 100 || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()

        data.clipData?.let {
            for (i in 0 until it.itemCount) {
                uris.add(it.getItemAt(i).uri)
            }
        }

        data.data?.let {
            if (!uris.contains(it)) uris.add(it)
        }

        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val name = queryName(uri)
            if (tracks.none { it.second == uri }) {
                tracks.add(name to uri)
            }
        }

        refreshPlaylist()

        if (index == -1 && tracks.isNotEmpty()) {
            playAt(0)
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: "Unknown track"
            }
        }
        return uri.lastPathSegment ?: "Unknown track"
    }

    private fun refreshPlaylist() {
        playlist.removeAllViews()

        if (tracks.isEmpty()) {
            playlist.addView(
                label("  Playlist empty. Tap + ADD MP3", green, 14f)
            )
        }

        tracks.forEachIndexed { i, track ->
            val row = label(
                "  ${i + 1}. ${track.first}",
                if (i == index) Color.WHITE else green,
                14f
            ).apply {
                setPadding(dp(7), dp(11), dp(7), dp(11))
                typeface = Typeface.MONOSPACE

                if (i == index) {
                    setBackgroundColor(Color.rgb(47, 83, 55))
                }

                setOnClickListener { playAt(i) }
            }
            playlist.addView(row, full())
        }
    }

    private fun playAt(i: Int) {
        if (i !in tracks.indices) return

        prepared = false
        player?.release()
        player = null
        index = i
        seek.progress = 0
        playButton.text = "▶"
        time.text = "00:00 / 00:00"

        try {
            val p = MediaPlayer()
            p.setDataSource(this, tracks[i].second)

            p.setOnPreparedListener {
                prepared = true
                seek.max = it.duration.coerceAtLeast(1)
                it.start()
                playButton.text = "Ⅱ"
            }

            p.setOnCompletionListener {
                if (repeat) {
                    playAt(index)
                } else {
                    move(1)
                }
            }

            p.setOnErrorListener { _, _, _ ->
                prepared = false
                playButton.text = "▶"
                Toast.makeText(
                    this,
                    "Unable to play file",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }

            player = p
            p.prepareAsync()

            title.text = tracks[i].first.uppercase(Locale.getDefault())
            refreshPlaylist()

        } catch (e: Exception) {
            prepared = false
            player?.release()
            player = null
            Toast.makeText(
                this,
                "Cannot open audio: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggle() {
        val p = player ?: run {
            if (tracks.isNotEmpty()) playAt(0) else pickAudio()
            return
        }

        if (!prepared) return

        if (p.isPlaying) {
            p.pause()
            playButton.text = "▶"
        } else {
            p.start()
            playButton.text = "Ⅱ"
        }
    }

    private fun stop() {
        if (prepared) {
            player?.pause()
            player?.seekTo(0)
            seek.progress = 0
            time.text = "00:00 / ${fmt(player?.duration ?: 0)}"
        }
        playButton.text = "▶"
    }

    private fun move(delta: Int) {
        if (tracks.isEmpty()) return

        val next = if (shuffle && tracks.size > 1) {
            tracks.indices.filter { it != index }.random()
        } else {
            index + delta
        }

        if (next in tracks.indices) {
            playAt(next)
        } else {
            stop()
            if (delta > 0) {
                index = -1
                refreshPlaylist()
            }
        }
    }

    private fun fmt(ms: Int): String =
        "%02d:%02d".format(
            Locale.US,
            ms / 60000,
            (ms / 1000) % 60
        )

    private fun label(
        value: String,
        color: Int,
        size: Float
    ) = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = size
        setPadding(dp(4), dp(7), dp(4), dp(7))
    }

    private fun full() = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        prepared = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
