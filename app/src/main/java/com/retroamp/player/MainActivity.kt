
package com.retroamp.player

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
    private var currentPosition = 0
    private var currentDuration = 0
    private var artworkRequest = 0
    private var scanning = false

    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var time: TextView
    private lateinit var playlist: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button
    private lateinit var cover: ImageView
    private lateinit var scanButton: Button
    private lateinit var libraryStatus: TextView

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

            currentPosition = intent.getIntExtra(
                MusicService.EXTRA_POSITION, 0
            )

            currentDuration = intent.getIntExtra(
                MusicService.EXTRA_DURATION, 0
            )

            title.text = intent.getStringExtra(
                MusicService.EXTRA_TITLE
            ) ?: "RETROAMP"

            artist.text = intent.getStringExtra(
                MusicService.EXTRA_ARTIST
            ) ?: "Artista desconhecido"

            album.text = intent.getStringExtra(
                MusicService.EXTRA_ALBUM
            ) ?: "Álbum desconhecido"

            playButton.text = if (playing) "Ⅱ" else "▶"

            seek.max = currentDuration.coerceAtLeast(1)

            if (!seeking) {
                seek.progress = currentPosition
            }

            time.text =
                "${fmt(currentPosition)} / ${fmt(currentDuration)}"

            if (changed) {
                refreshPlaylist()
                loadArtwork()
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
        currentPosition = prefs().getInt("saved_position", 0)
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
            if (extra != null) putExtra(extraName, extra)
        }

        if (
            action == MusicService.PLAY ||
            action == MusicService.PLAY_INDEX ||
            action == MusicService.TOGGLE
        ) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // AUTO SCAN 1.5

    private fun audioPermission(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasAudioPermission(): Boolean {
        return checkSelfPermission(
            audioPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioScan() {
        if (scanning) return

        if (hasAudioPermission()) {
            scanMusic()
        } else {
            requestPermissions(
                arrayOf(audioPermission()),
                301
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 301) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {
                scanMusic()
            } else {
                Toast.makeText(
                    this,
                    "Permita acesso aos áudios para usar o Auto Scan",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun scanMusic() {
        if (scanning || !hasAudioPermission()) return

        scanning = true
        scanButton.isEnabled = false
        scanButton.text = "BUSCANDO..."
        libraryStatus.text = "Procurando músicas no celular..."

        Thread {
            val found = mutableListOf<Pair<String, Uri>>()
            var error: String? = null

            try {
                val collection =
                    if (Build.VERSION.SDK_INT >= 29) {
                        MediaStore.Audio.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL
                        )
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME
                )

                val selection =
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0"

                val order =
                    "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"

                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    order
                )?.use { cursor ->

                    val idColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media._ID
                    )

                    val nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.DISPLAY_NAME
                    )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                            ?: "Música $id"

                        val uri = ContentUris.withAppendedId(
                            collection, id
                        )

                        found.add(name to uri)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Erro ao buscar músicas"
            }

            val scanError = error

            runOnUiThread {
                scanning = false
                scanButton.isEnabled = true
                scanButton.text = "⟳ AUTO SCAN"

                if (scanError != null) {
                    libraryStatus.text =
                        "Não foi possível concluir a busca"

                    Toast.makeText(
                        this,
                        scanError,
                        Toast.LENGTH_LONG
                    ).show()

                    return@runOnUiThread
                }

                // Preserva a playlist atual e adiciona
                // apenas os arquivos que ainda não existem.
                val existing = tracks.map {
                    it.second.toString()
                }.toMutableSet()

                var added = 0

                for (song in found) {
                    if (existing.add(song.second.toString())) {
                        tracks.add(song)
                        added++
                    }
                }

                savePlaylist()
                refreshPlaylist()

                // Avisa o serviço para recarregar a playlist.
                // Não interrompe a música atual.
                if (serviceRunning()) {
                    command(MusicService.REFRESH)
                }

                libraryStatus.text =
                    "${tracks.size} músicas na biblioteca"

                prefs().edit()
                    .putBoolean("auto_scan_done", true)
                    .apply()

                Toast.makeText(
                    this,
                    "$added novas músicas encontradas!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun serviceRunning(): Boolean {
        val manager = getSystemService(
            ACTIVITY_SERVICE
        ) as android.app.ActivityManager

        return manager.getRunningServices(
            Int.MAX_VALUE
        ).any {
            it.service.className ==
                MusicService::class.java.name
        }
    }

    private fun defaultCover(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            480, 480, Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(7, 24, 14))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = green
            textAlign = Paint.Align.CENTER
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f

        canvas.drawRoundRect(
            20f, 20f, 460f, 460f, 22f, 22f, paint
        )

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(
            Typeface.MONOSPACE, Typeface.BOLD
        )

        paint.textSize = 56f
        canvas.drawText("RETROAMP", 240f, 180f, paint)

        paint.textSize = 130f
        canvas.drawText("♫", 240f, 320f, paint)

        paint.textSize = 23f
        canvas.drawText(
            "BY MATHEUSOBLACK", 240f, 410f, paint
        )

        return bitmap
    }

    private fun loadArtwork() {
        val request = ++artworkRequest
        val uri = tracks.getOrNull(index)?.second

        cover.setImageBitmap(defaultCover())

        if (uri == null) return

        Thread {
            var bitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(this, uri)
                val bytes = retriever.embeddedPicture

                if (bytes != null) {
                    val bounds = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size, bounds
                    )

                    val maxSide = maxOf(
                        bounds.outWidth, bounds.outHeight
                    )

                    val sample = (
                        maxSide / 800
                    ).coerceAtLeast(1)

                    bitmap = BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                        }
                    )
                }
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            handler.post {
                if (
                    request == artworkRequest &&
                    !isFinishing &&
                    !isDestroyed
                ) {
                    cover.setImageBitmap(
                        bitmap ?: defaultCover()
                    )
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPlaylist()

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(25, 25, 28))
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        setContentView(root)

        val header = label(
            "▣ RETROAMP   •   CLASSIC PLAYER",
            silver, 17f
        ).apply {
            setTypeface(
                Typeface.MONOSPACE, Typeface.BOLD
            )
            gravity = Gravity.CENTER
            setPadding(
                dp(8), dp(10), dp(8), dp(10)
            )
            setBackgroundColor(Color.rgb(65, 65, 73))
        }
        root.addView(header, full())

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(12), dp(8), dp(12), dp(6)
            )
            setBackgroundColor(Color.rgb(5, 22, 13))
        }
        root.addView(display, full())

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(defaultCover())
            contentDescription = "Capa do álbum"
            setBackgroundColor(Color.rgb(7, 24, 14))
        }

        val coverSize = minOf(
            resources.displayMetrics.widthPixels - dp(72),
            dp(215)
        )

        display.addView(
            cover,
            LinearLayout.LayoutParams(
                coverSize, coverSize
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        )

        title = label("RETROAMP", green, 17f).apply {
            gravity = Gravity.CENTER
            setTypeface(
                Typeface.MONOSPACE, Typeface.BOLD
            )
            maxLines = 2
        }
        display.addView(title, full())

        artist = label(
            "Artista desconhecido",
            Color.WHITE, 14f
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        display.addView(artist, full())

        album = label(
            "Álbum desconhecido",
            silver, 12f
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        display.addView(album, full())

        // Equalizador animado de 24 barras.
        val equalizer = object : View(this) {

            private val paint = Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = green
            }

            private val bars = FloatArray(24) { 0.08f }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                val gap = dp(3).toFloat()

                val barWidth = (
                    (width - gap * 23) / 24f
                ).coerceAtLeast(1f)

                val maxHeight = height * 0.88f

                for (i in bars.indices) {
                    val target = if (playing) {
                        Random.nextFloat() * 0.85f + 0.15f
                    } else {
                        0.08f
                    }

                    bars[i] += (
                        target - bars[i]
                    ) * 0.45f

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
            LinearLayout.LayoutParams(
                -1, dp(48)
            ).apply {
                topMargin = dp(6)
            }
        )

        time = label(
            "00:00 / 00:00", green, 13f
        ).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        root.addView(time, full())

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(green)
        }
        root.addView(seek, full())

        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(
                    s: SeekBar?
                ) {
                    seeking = true
                }

                override fun onStopTrackingTouch(
                    s: SeekBar?
                ) {
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
                ) {
                    if (fromUser) {
                        time.text =
                            "${fmt(progress)} / ${fmt(currentDuration)}"
                    }
                }
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
                LinearLayout.LayoutParams(
                    0, dp(50), 1f
                )
            )

            setOnClickListener { action() }
        }

        control("|◀") {
            command(MusicService.PREVIOUS)
        }

        playButton = control("▶") {
            if (tracks.isEmpty()) {
                requestAudioScan()
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
            textSize = 12f
            setOnClickListener { pickAudio() }
        }

        options.addView(
            add,
            LinearLayout.LayoutParams(
                0, dp(48), 2f
            )
        )

        val random = Button(this).apply {
            text = if (shuffle) {
                "SHUFFLE ON"
            } else {
                "SHUFFLE OFF"
            }

            textSize = 10f
            isAllCaps = false

            setOnClickListener {
                shuffle = !shuffle
                text = if (shuffle) {
                    "SHUFFLE ON"
                } else {
                    "SHUFFLE OFF"
                }
                saveOptions()
                if (serviceRunning()) {
                    command(MusicService.REFRESH)
                }
            }
        }

        options.addView(
            random,
            LinearLayout.LayoutParams(
                0, dp(48), 1.5f
            )
        )

        val loop = Button(this).apply {
            text = if (repeat) {
                "REPEAT ON"
            } else {
                "REPEAT OFF"
            }

            textSize = 10f
            isAllCaps = false

            setOnClickListener {
                repeat = !repeat
                text = if (repeat) {
                    "REPEAT ON"
                } else {
                    "REPEAT OFF"
                }
                saveOptions()
                if (serviceRunning()) {
                    command(MusicService.REFRESH)
                }
            }
        }

        options.addView(
            loop,
            LinearLayout.LayoutParams(
                0, dp(48), 1.5f
            )
        )

        // Nova área de biblioteca musical.
        val libraryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(65, 65, 73))
        }

        root.addView(libraryBar, full())

        libraryStatus = label(
            "${tracks.size} músicas na biblioteca",
            silver, 12f
        )

        libraryBar.addView(
            libraryStatus,
            LinearLayout.LayoutParams(
                0, -2, 1f
            )
        )

        scanButton = Button(this).apply {
            text = "⟳ AUTO SCAN"
            textSize = 11f
            isAllCaps = false
            setTextColor(green)
            setOnClickListener {
                requestAudioScan()
            }
        }

        libraryBar.addView(
            scanButton,
            LinearLayout.LayoutParams(
                dp(125), dp(45)
            )
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 19, 13))
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        )

        playlist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(playlist)

        if (index in tracks.indices) {
            title.text = tracks[index].first
        }

        refreshPlaylist()
        loadArtwork()

        root.addView(
            label(
                "RETROAMP 1.5  •  AUTO SCAN",
                silver, 11f
            ).apply {
                gravity = Gravity.CENTER
            },
            full()
        )

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                200
            )
        }

        // Primeira abertura da versão 1.5:
        // pede autorização e faz a busca automaticamente.
        if (!prefs().getBoolean("auto_scan_done", false)) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    requestAudioScan()
                }
            }, 500)
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

        if (serviceRunning()) {
            command(MusicService.REFRESH)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}

        super.onStop()
    }

    override fun onDestroy() {
        artworkRequest++
        super.onDestroy()
    }

    private fun pickAudio() {
        val intent = Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE, true
            )
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

        if (
            requestCode != 100 ||
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
            if (!uris.contains(it)) {
                uris.add(it)
            }
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

        libraryStatus.text =
            "${tracks.size} músicas na biblioteca"

        if (serviceRunning()) {
            command(MusicService.REFRESH)
        }

        if (index == -1 && tracks.isNotEmpty()) {
            command(MusicService.PLAY_INDEX, 0)
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
                    "  Nenhuma música. Use AUTO SCAN.",
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
                    dp(7), dp(10), dp(7), dp(10)
                )
                typeface = Typeface.MONOSPACE

                if (i == index) {
                    setBackgroundColor(
                        Color.rgb(47, 83, 55)
                    )
                }

                setOnClickListener {
                    command(
                        MusicService.PLAY_INDEX, i
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
            dp(4), dp(5), dp(4), dp(5)
        )
    }

    private fun full() =
        LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
