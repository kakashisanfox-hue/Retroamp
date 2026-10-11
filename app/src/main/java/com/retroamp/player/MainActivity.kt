
package com.retroamp.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.util.Locale
import kotlin.math.hypot

class MainActivity : Activity() {

    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)

    private val tracks = mutableListOf<Pair<String, Uri>>()
    private var index = -1
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var shuffle = false
    private var repeat = false
    private var seeking = false
    private var prepared = false

    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var playlist: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button
    private lateinit var equalizer: TextView

    private val bars = IntArray(24)
    private val handler = Handler(Looper.getMainLooper())

    private val symbols = charArrayOf(
        '\u2581', '\u2582', '\u2583', '\u2584',
        '\u2585', '\u2586', '\u2587', '\u2588'
    )

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && prepared) {
                try {
                    if (!seeking) {
                        seek.progress = p.currentPosition
                            .coerceAtMost(seek.max)
                    }
                    time.text =
                        "${fmt(p.currentPosition)} / ${fmt(p.duration.coerceAtLeast(0))}"

                    if (!p.isPlaying) resetBars()
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 100)
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

        equalizer = label("▁".repeat(24), green, 22f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(dp(2), dp(16), dp(2), dp(20))
            maxLines = 1
        }
        display.addView(equalizer, full())

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

        fun control(
            symbol: String,
            action: () -> Unit
        ): Button = Button(this).apply {
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
                text = if (shuffle) "SHUFFLE ON"
                       else "SHUFFLE OFF"
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
                text = if (repeat) "REPEAT ON"
                       else "REPEAT OFF"
            }
        }
        options.addView(
            loop,
            LinearLayout.LayoutParams(0, dp(55), 1.5f)
        )

        root.addView(
            label("  PLAYLIST EDITOR", silver, 14f).apply {
                setBackgroundColor(Color.rgb(65, 65, 73))
                setPadding(4, 12, 4, 12)
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
            label(
                "RETROAMP 1.1  •  LOCAL AUDIO",
                silver, 11f
            ).apply {
                gravity = Gravity.CENTER
            },
            full()
        )

        handler.post(ticker)

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                101
            )
        }
    }

    private fun setupVisualizer(p: MediaPlayer) {
        releaseVisualizer()

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val v = Visualizer(p.audioSessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]

            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {}

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 4) return

                        val values = IntArray(24)
                        val bins = fft.size / 2

                        for (i in values.indices) {
                            val start = 1 + (i * i * (bins - 2)) /
                                (24 * 24)
                            val end = 1 + ((i + 1) * (i + 1) *
                                (bins - 2)) / (24 * 24)

                            var peak = 0.0

                            for (j in start until end.coerceAtLeast(start + 1)) {
                                if (j >= bins) break
                                val re = fft[j * 2].toDouble()
                                val im = fft[j * 2 + 1].toDouble()
                                val magnitude = hypot(re, im)
                                if (magnitude > peak) peak = magnitude
                            }

                            values[i] = when {
                                peak < 2 -> 0
                                peak < 6 -> 1
                                peak < 12 -> 2
                                peak < 22 -> 3
                                peak < 36 -> 4
                                peak < 55 -> 5
                                peak < 80 -> 6
                                else -> 7
                            }
                        }

                        handler.post {
                            if (player !== p) return@post
                            if (!p.isPlaying) return@post

                            for (i in bars.indices) {
                                bars[i] = if (values[i] >= bars[i]) {
                                    values[i]
                                } else {
                                    (bars[i] - 1).coerceAtLeast(0)
                                }
                            }
                            renderBars()
                        }
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true
            )

            v.enabled = true
            visualizer = v
        } catch (_: Exception) {
            releaseVisualizer()
        }
    }

    private fun renderBars() {
        equalizer.text = buildString {
            for (value in bars) {
                append(symbols[value.coerceIn(0, 7)])
            }
        }
    }

    private fun resetBars() {
        if (bars.any { it != 0 }) {
            bars.fill(0)
            renderBars()
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )
        if (requestCode == 101 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            if (prepared) {
                player?.let { setupVisualizer(it) }
            }
        }
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

        if (requestCode != 100 ||
            resultCode != RESULT_OK || data == null
        ) return

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
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val name = queryName(uri)
            if (tracks.none { it.second == uri }) {
                tracks.add(name to uri)
            }
        }

        refreshPlaylist()
        if (index == -1 && tracks.isNotEmpty()) playAt(0)
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0) ?: "Unknown track"
            }
        }
        return uri.lastPathSegment ?: "Unknown track"
    }

    private fun refreshPlaylist() {
        playlist.removeAllViews()

        if (tracks.isEmpty()) {
            playlist.addView(
                label(
                    "  Playlist empty. Tap + ADD MP3",
                    green, 14f
                )
            )
        }

        tracks.forEachIndexed { i, track ->
            val row = label(
                "  ${i + 1}. ${track.first}",
                if (i == index) Color.WHITE else green,
                14f
            ).apply {
                setPadding(
                    dp(7), dp(11), dp(7), dp(11)
                )
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

        releaseVisualizer()
        player?.release()
        player = null
        prepared = false
        resetBars()
        index = i

        try {
            val p = MediaPlayer()
            p.setDataSource(this, tracks[i].second)

            p.setOnPreparedListener {
                prepared = true
                seek.max = it.duration.coerceAtLeast(1)
                setupVisualizer(it)
                it.start()
                playButton.text = "Ⅱ"
            }

            p.setOnCompletionListener {
                if (repeat) playAt(index)
                else move(1)
            }

            p.setOnErrorListener { _, _, _ ->
                prepared = false
                releaseVisualizer()
                Toast.makeText(
                    this,
                    "Unable to play file",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }

            player = p
            p.prepareAsync()

            title.text = tracks[i].first.uppercase(
                Locale.getDefault()
            )
            refreshPlaylist()

        } catch (e: Exception) {
            prepared = false
            Toast.makeText(
                this,
                "Cannot open audio: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggle() {
        val p = player ?: run {
            if (tracks.isNotEmpty()) playAt(0)
            else pickAudio()
            return
        }

        if (!prepared) return

        if (p.isPlaying) {
            p.pause()
            playButton.text = "▶"
            resetBars()
        } else {
            p.start()
            playButton.text = "Ⅱ"
        }
    }

    private fun stop() {
        if (prepared) {
            player?.pause()
            player?.seekTo(0)
        }
        playButton.text = "▶"
        resetBars()
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
                releaseVisualizer()
                player?.release()
                player = null
                prepared = false
                index = -1
                refreshPlaylist()
            }
        }
    }

    private fun fmt(ms: Int) = "%02d:%02d".format(
        Locale.US, ms / 60000, (ms / 1000) % 60
    )

    private fun label(
        s: String,
        color: Int,
        size: Float
    ) = TextView(this).apply {
        text = s
        setTextColor(color)
        textSize = size
        setPadding(dp(4), dp(7), dp(4), dp(7))
    }

    private fun full() =
        LinearLayout.LayoutParams(-1, -2)

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releaseVisualizer()
        player?.release()
        super.onDestroy()
    }
}
