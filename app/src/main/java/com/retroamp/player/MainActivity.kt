
package com.retroamp.player

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)

    private val tracks = mutableListOf<Pair<String, Uri>>()

    private var index = -1
    private var shuffle = false
    private var repeat = false
    private var seeking = false
    private var playing = false
    private var ready = false
    private var currentPosition = 0
    private var currentDuration = 0

    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var playlist: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            if (intent?.action != MusicService.UPDATE) return

            val newIndex = intent.getIntExtra(
                MusicService.EXTRA_INDEX, -1
            )

            val changed = newIndex != index
            index = newIndex

            playing = intent.getBooleanExtra(
                MusicService.EXTRA_PLAYING, false
            )

            ready = intent.getBooleanExtra(
                MusicService.EXTRA_READY, false
            )

            currentPosition = intent.getIntExtra(
                MusicService.EXTRA_POSITION, 0
            )

            currentDuration = intent.getIntExtra(
                MusicService.EXTRA_DURATION, 0
            )

            title.text = intent.getStringExtra(
                MusicService.EXTRA_TITLE
            )?.uppercase(Locale.getDefault()) ?: "RETROAMP"

            playButton.text = if (playing) "Ⅱ" else "▶"

            seek.max = currentDuration.coerceAtLeast(1)

            if (!seeking) {
                seek.progress = currentPosition
            }

            time.text =
                "${fmt(currentPosition)} / ${fmt(currentDuration)}"

            if (changed) {
                refreshPlaylist()
            }
        }
    }

    private fun prefs() =
        getSharedPreferences("MainActivity", MODE_PRIVATE)

    private fun loadPlaylist() {
        tracks.clear()

        try {
            val array = JSONArray(
                prefs().getString("saved_playlist", "[]") ?: "[]"
            )

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                tracks.add(
                    item.getString("name") to
                        Uri.parse(item.getString("uri"))
                )
            }
        } catch (_: Exception) {}

        index = prefs().getInt("saved_index", -1)
        if (index !in tracks.indices) index = -1

        shuffle = prefs().getBoolean("shuffle", false)
        repeat = prefs().getBoolean("repeat", false)

        currentPosition =
            prefs().getInt("saved_position", 0)
    }

    private fun savePlaylist() {
        val array = JSONArray()

        tracks.forEach { (name, uri) ->
            array.put(
                JSONObject().apply {
                    put("name", name)
                    put("uri", uri.toString())
                }
            )
        }

        prefs().edit()
            .putString("saved_playlist", array.toString())
            .apply()
    }

    private fun saveOptions() {
        prefs().edit()
            .putBoolean("shuffle", shuffle)
            .putBoolean("repeat", repeat)
            .apply()
    }

    private fun command(
        action: String,
        extra: Int? = null,
        extraName: String = MusicService.EXTRA_INDEX
    ) {
        val intent = Intent(
            this, MusicService::class.java
        ).apply {
            this.action = action

            if (extra != null) {
                putExtra(extraName, extra)
            }
        }

        if (action == MusicService.PLAY ||
            action == MusicService.PLAY_INDEX ||
            action == MusicService.TOGGLE
        ) {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            startService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPlaylist()

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

        val equalizer = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green
            }

            private val bars = FloatArray(24) { 0.08f }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                val gap = dp(3).toFloat()
                val barWidth =
                    ((width - gap * 23) / 24f).coerceAtLeast(1f)
                val maxHeight = height * 0.85f

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
                    command(
                        MusicService.SEEK,
                        seek.progress,
                        MusicService.EXTRA_POSITION
                    )
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

        control("|◀") {
            command(MusicService.PREVIOUS)
        }

        playButton = control("▶") {
            if (tracks.isEmpty()) {
                pickAudio()
            } else {
                command(MusicService.TOGGLE)
            }
        }

        control("■") {
            command(MusicService.STOP)
        }

        control("▶|") {
            command(MusicService.NEXT)
        }

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
            text = if (shuffle) "SHUFFLE ON" else "SHUFFLE OFF"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                shuffle = !shuffle
                text = if (shuffle) "SHUFFLE ON"
                else "SHUFFLE OFF"
                saveOptions()
                command(MusicService.REFRESH)
            }
        }
        options.addView(
            random,
            LinearLayout.LayoutParams(0, dp(55), 1.5f)
        )

        val loop = Button(this).apply {
            text = if (repeat) "REPEAT ON" else "REPEAT OFF"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                repeat = !repeat
                text = if (repeat) "REPEAT ON"
                else "REPEAT OFF"
                saveOptions()
                command(MusicService.REFRESH)
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

        if (index in tracks.indices) {
            title.text =
                tracks[index].first.uppercase(Locale.getDefault())
        }

        refreshPlaylist()

        root.addView(
            label("RETROAMP 1.3  •  LOCAL AUDIO", silver, 11f).apply {
                gravity = Gravity.CENTER
            },
            full()
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                200
            )
        }
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(MusicService.UPDATE)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(receiver, filter)
        }

        // Consulta o serviço caso já esteja em execução.
        // Evita iniciar uma segunda reprodução.
        val manager = getSystemService(
            Activity.ACTIVITY_SERVICE
        ) as android.app.ActivityManager

        val running = manager.getRunningServices(
            Int.MAX_VALUE
        ).any {
            it.service.className ==
                MusicService::class.java.name
        }

        if (running) {
            command(MusicService.REFRESH)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}

        super.onStop()
    }

    private fun pickAudio() {
        val intent = Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {
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
        super.onActivityResult(
            requestCode, resultCode, data
        )

        if (requestCode != 100 ||
            resultCode != RESULT_OK ||
            data == null
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
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val name = try {
                queryName(uri)
            } catch (_: Exception) {
                uri.lastPathSegment ?: "Unknown track"
            }

            if (tracks.none { it.second == uri }) {
                tracks.add(name to uri)
            }
        }

        savePlaylist()
        refreshPlaylist()

        command(MusicService.REFRESH)

        if (index == -1 && tracks.isNotEmpty()) {
            command(
                MusicService.PLAY_INDEX,
                0
            )
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
                    ?: "Unknown track"
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
                    setBackgroundColor(
                        Color.rgb(47, 83, 55)
                    )
                }

                setOnClickListener {
                    command(
                        MusicService.PLAY_INDEX,
                        i
                    )
                }
            }

            playlist.addView(row, full())
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
        setPadding(
            dp(4), dp(7), dp(4), dp(7)
        )
    }

    private fun full() =
        LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
