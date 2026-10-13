
package com.retroamp.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
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
    private val namedPlaylists = linkedMapOf<String, MutableList<android.net.Uri>>()


    private val green = Color.rgb(108, 255, 85)
    private val silver = Color.rgb(207, 207, 213)
    private val tracks = mutableListOf<Pair<String, Uri>>()

    private val minimumScanDurationMs = 120_000L

    private var index = -1
    private var shuffle = false
    private var repeat = false
    private var seeking = false
    private var playing = false
    private var currentPosition = 0
    private var currentDuration = 0
    private var artworkRequest = 0
    private var scanning = false
    private var receiverRegistered = false

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

        if (index !in tracks.indices) {
            index = -1
        }

        shuffle = prefs().getBoolean("shuffle", false)
        repeat = prefs().getBoolean("repeat", false)
        currentPosition = prefs().getInt("saved_position", 0)
    }


    private fun saveNamedPlaylists() {
        val json = org.json.JSONObject()
        namedPlaylists.forEach { (name, songs) ->
            val array = org.json.JSONArray()
            songs.forEach { uri ->
                array.put(uri.toString())
            }
            json.put(name, array)
        }
        prefs().edit()
            .putString("named_playlists", json.toString())
            .apply()
    }

    private fun loadNamedPlaylists() {
        namedPlaylists.clear()
        try {
            val json = org.json.JSONObject(
                prefs().getString("named_playlists", "{}") ?: "{}"
            )
            val names = json.keys()
            while (names.hasNext()) {
                val name = names.next()
                val array = json.getJSONArray(name)
                val songs = mutableListOf<android.net.Uri>()
                for (i in 0 until array.length()) {
                    songs.add(android.net.Uri.parse(array.getString(i)))
                }
                namedPlaylists[name] = songs
            }
        } catch (_: Exception) {}
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

    private fun serviceRunning(): Boolean {
        return try {
            val manager = getSystemService(
                ACTIVITY_SERVICE
            ) as ActivityManager

            manager.getRunningServices(
                Int.MAX_VALUE
            ).any {
                it.service.className ==
                    MusicService::class.java.name
            }
        } catch (_: Exception) {
            false
        }
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

        try {
            if (
                action == MusicService.PLAY ||
                action == MusicService.PLAY_INDEX ||
                action == MusicService.TOGGLE
            ) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Não foi possível executar o comando",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmRemove(position: Int) {
        val song = tracks.getOrNull(position) ?: return

        AlertDialog.Builder(this)
            .setTitle("Remover música?")
            .setMessage(
                "Remover \"${song.first}\" da playlist?\n\n" +
                    "O arquivo original continuará no celular."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                removeTrack(position)
            }
            .show()
    }

    private fun removeTrack(position: Int) {
        if (position !in tracks.indices) return

        val oldIndex = index
        val wasPlaying = playing
        val oldPosition = currentPosition
        val running = serviceRunning()

        if (running) {
            stopService(
                Intent(this, MusicService::class.java)
            )
        }

        tracks.removeAt(position)

        index = when {
            tracks.isEmpty() -> -1
            oldIndex < 0 -> -1
            position < oldIndex -> oldIndex - 1
            position == oldIndex -> -1
            else -> oldIndex
        }

        currentPosition =
            if (position == oldIndex) 0 else oldPosition

        playing = false

        prefs().edit()
            .putInt("saved_index", index)
            .putInt("saved_position", currentPosition)
            .apply()

        savePlaylist()
        refreshPlaylist()
        updateLibraryStatus()

        if (index == -1) {
            title.text = "RETROAMP"
            artist.text = "Artista desconhecido"
            album.text = "Álbum desconhecido"
            currentDuration = 0
            currentPosition = 0
            time.text = "00:00 / 00:00"
            seek.progress = 0
            playButton.text = "▶"
        }

        loadArtwork()

        if (wasPlaying && index in tracks.indices) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    command(MusicService.PLAY_INDEX, index)

                    if (oldPosition > 0) {
                        handler.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                command(
                                    MusicService.SEEK,
                                    oldPosition,
                                    MusicService.EXTRA_POSITION
                                )
                            }
                        }, 900)
                    }
                }
            }, 300)
        }

        Toast.makeText(
            this,
            "Música removida da playlist",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmClearPlaylist() {
        if (tracks.isEmpty()) {
            Toast.makeText(
                this,
                "A playlist já está vazia",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Limpar playlist?")
            .setMessage(
                "Remover todas as ${tracks.size} músicas " +
                    "da biblioteca do RetroAmp?\n\n" +
                    "Nenhum MP3 será apagado do celular. " +
                    "Você poderá usar o Auto Scan novamente."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Limpar tudo") { _, _ ->
                clearPlaylist()
            }
            .show()
    }

    private fun clearPlaylist() {
        stopService(
            Intent(this, MusicService::class.java)
        )

        tracks.clear()
        index = -1
        playing = false
        currentPosition = 0
        currentDuration = 0

        prefs().edit()
            .putInt("saved_index", -1)
            .putInt("saved_position", 0)
            .apply()

        savePlaylist()
        refreshPlaylist()
        updateLibraryStatus()
        loadArtwork()

        title.text = "RETROAMP"
        artist.text = "Artista desconhecido"
        album.text = "Álbum desconhecido"
        time.text = "00:00 / 00:00"
        seek.progress = 0
        seek.max = 1
        playButton.text = "▶"

        Toast.makeText(
            this,
            "Playlist limpa com sucesso",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateLibraryStatus() {
        libraryStatus.text =
            "${tracks.size} músicas na biblioteca"
    }

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
                    "Autorize o acesso às músicas para usar o Auto Scan",
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
        libraryStatus.text =
            "Buscando músicas de 2 minutos ou mais..."

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
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION
                )

                val selection =
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                        "${MediaStore.Audio.Media.DURATION} >= ?"

                val selectionArgs = arrayOf(
                    minimumScanDurationMs.toString()
                )

                val order =
                    "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"

                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    order
                )?.use { cursor ->

                    val idColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media._ID
                        )

                    val nameColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DISPLAY_NAME
                        )

                    val durationColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DURATION
                        )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)

                        val name = cursor.getString(nameColumn)
                            ?: "Música $id"

                        val duration = cursor.getLong(
                            durationColumn
                        )

                        if (duration < minimumScanDurationMs) {
                            continue
                        }

                        val uri = ContentUris.withAppendedId(
                            collection,
                            id
                        )

                        found.add(name to uri)
                    }
                }

            } catch (e: Exception) {
                error = e.message ?: "Erro no Auto Scan"
            }

            val scanError = error

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                scanning = false
                scanButton.isEnabled = true
                scanButton.text = "⟳ AUTO SCAN"

                if (scanError != null) {
                    updateLibraryStatus()

                    Toast.makeText(
                        this,
                        scanError,
                        Toast.LENGTH_LONG
                    ).show()

                    return@runOnUiThread
                }

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
                updateLibraryStatus()

                if (serviceRunning()) {
                    command(MusicService.REFRESH)
                }

                prefs().edit()
                    .putBoolean("auto_scan_done", true)
                    .apply()

                Toast.makeText(
                    this,
                    "$added novas músicas encontradas! " +
                        "Filtro: 2 minutos ou mais.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
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
            Typeface.MONOSPACE,
            Typeface.BOLD
        )

        paint.textSize = 56f
        canvas.drawText(
            "RETROAMP", 240f, 180f, paint
        )

        paint.textSize = 130f
        canvas.drawText("♫", 240f, 320f, paint)

        paint.textSize = 23f
        canvas.drawText(
            "BY MATHEUSOBLACK",
            240f, 410f, paint
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
                        bounds.outWidth,
                        bounds.outHeight
                    )

                    val sample =
                        (maxSide / 800).coerceAtLeast(1)

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
        loadNamedPlaylists()

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(25, 25, 28))
            setPadding(
                dp(12), dp(12), dp(12), dp(8)
            )
        }

        setContentView(root)

        val header = label(
            "▣ RETROAMP   •   CLASSIC PLAYER",
            silver, 17f
        ).apply {
            setTypeface(
                Typeface.MONOSPACE,
                Typeface.BOLD
            )
            gravity = Gravity.CENTER
            setPadding(
                dp(8), dp(10), dp(8), dp(10)
            )
            setBackgroundColor(
                Color.rgb(65, 65, 73)
            )
        }

        root.addView(header, full())

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(12), dp(8), dp(12), dp(6)
            )
            setBackgroundColor(
                Color.rgb(5, 22, 13)
            )
        }

        root.addView(display, full())

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(defaultCover())
            contentDescription = "Capa do álbum"
            setBackgroundColor(
                Color.rgb(7, 24, 14)
            )
        }

        val coverSize = minOf(
            resources.displayMetrics.widthPixels - dp(72),
            dp(210)
        )

        display.addView(
            cover,
            LinearLayout.LayoutParams(
                coverSize,
                coverSize
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        )

        title = label(
            "RETROAMP", green, 17f
        ).apply {
            gravity = Gravity.CENTER
            setTypeface(
                Typeface.MONOSPACE,
                Typeface.BOLD
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

        val equalizer = object : View(this) {

            private val paint = Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = green
            }

            private val bars = FloatArray(24) {
                0.08f
            }

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
                    val top =
                        height - bars[i] * maxHeight

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
                -1, dp(45)
            ).apply {
                topMargin = dp(6)
            }
        )

        time = label(
            "00:00 / 00:00",
            green, 13f
        ).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }

        root.addView(time, full())

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList =
                ColorStateList.valueOf(green)
            thumbTintList =
                ColorStateList.valueOf(green)
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

            backgroundTintList =
                ColorStateList.valueOf(
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

        val libraryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(
                Color.rgb(65, 65, 73)
            )
        }

        root.addView(libraryBar, full())

        libraryStatus = label(
            "${tracks.size} músicas",
            silver, 11f
        )

        libraryBar.addView(
            libraryStatus,
            LinearLayout.LayoutParams(
                0, -2, 1f
            )
        )

        scanButton = Button(this).apply {
            text = "⟳ SCAN"
            textSize = 10f
            isAllCaps = false
            setTextColor(green)

            setOnClickListener {
                requestAudioScan()
            }
        }

        libraryBar.addView(
            scanButton,
            LinearLayout.LayoutParams(
                dp(90), dp(45)
            )
        )

        val clearButton = Button(this).apply {
            text = "LIMPAR"
            textSize = 10f
            isAllCaps = false
            setTextColor(
                Color.rgb(255, 155, 155)
            )

            setOnClickListener {
                confirmClearPlaylist()
            }
        }

        libraryBar.addView(
            clearButton,
            LinearLayout.LayoutParams(
                dp(85), dp(45)
            )
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(
                Color.rgb(11, 19, 13)
            )
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

        val newPlaylistButton = Button(this).apply {
            text = "+ NOVA PLAYLIST"
            isAllCaps = false
            setTextColor(green)
            setOnClickListener {
            val input = EditText(this@MainActivity).apply {
                hint = "Nome da playlist"
                setSingleLine(true)
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Nova playlist")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Criar") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                    if (!namedPlaylists.containsKey(name)) {
                        namedPlaylists[name] = mutableListOf()
                        saveNamedPlaylists()
                            recreate()
                        Toast.makeText(
                            this@MainActivity,
                            "Playlist criada: $name",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Essa playlist já existe",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                }
                .show()
        }
        }

        val playlistContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        playlistContainer.addView(newPlaylistButton)

        val namedPlaylistList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }


        namedPlaylists.forEach { (name, songs) ->
            val button = Button(this).apply {
                text = "♫ $name (${songs.size} músicas)"
                isAllCaps = false
                setOnClickListener {
                    val options = arrayOf(
                        "Adicionar músicas",
                        "Ver / remover músicas",
                        "Excluir playlist"
                    )
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(name)
                        .setItems(options) { _, choice ->
                            when (choice) {
                                0 -> {
                                    if (tracks.isEmpty()) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Use AUTO SCAN primeiro",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val labels = tracks.map {
                                            it.first
                                        }.toTypedArray()
                                        val selected = BooleanArray(
                                            tracks.size
                                        ) { i ->
                                            songs.contains(tracks[i].second)
                                        }
                                        AlertDialog.Builder(
                                            this@MainActivity
                                        )
                                            .setTitle("Adicionar a $name")
                                            .setMultiChoiceItems(
                                                labels, selected
                                            ) { _, which, checked ->
                                                selected[which] = checked
                                            }
                                            .setNegativeButton(
                                                "Cancelar", null
                                            )
                                            .setPositiveButton(
                                                "Salvar"
                                            ) { _, _ ->
                                                songs.clear()
                                                tracks.forEachIndexed { i, track ->
                                                    if (selected[i]) {
                                                        songs.add(track.second)
                                                    }
                                                }
                                                saveNamedPlaylists()
                                                recreate()
                                            }
                                            .show()
                                    }
                                }
                                1 -> {
                                    val labels = songs.map { uri ->
                                        tracks.find {
                                            it.second == uri
                                        }?.first ?: try {
                                            queryName(uri)
                                        } catch (_: Exception) {
                                            "Música"
                                        }
                                    }.toTypedArray()
                                    if (labels.isEmpty()) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Playlist vazia",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        AlertDialog.Builder(
                                            this@MainActivity
                                        )
                                            .setTitle(name)
                                            .setItems(labels) { _, pos ->
                                                AlertDialog.Builder(
                                                    this@MainActivity
                                                )
                                                    .setTitle(
                                                        "Remover música?"
                                                    )
                                                    .setMessage(labels[pos])
                                                    .setNegativeButton(
                                                        "Cancelar", null
                                                    )
                                                    .setPositiveButton(
                                                        "Remover"
                                                    ) { _, _ ->
                                                        songs.removeAt(pos)
                                                        saveNamedPlaylists()
                                                        recreate()
                                                    }
                                                    .show()
                                            }
                                            .setNegativeButton(
                                                "Fechar", null
                                            )
                                            .show()
                                    }
                                }
                                2 -> {
                                    AlertDialog.Builder(
                                        this@MainActivity
                                    )
                                        .setTitle("Excluir $name?")
                                        .setMessage(
                                            "As músicas do celular serão preservadas."
                                        )
                                        .setNegativeButton(
                                            "Cancelar", null
                                        )
                                        .setPositiveButton(
                                            "Excluir"
                                        ) { _, _ ->
                                            namedPlaylists.remove(name)
                                            saveNamedPlaylists()
                                            recreate()
                                        }
                                        .show()
                                }
                            }
                        }
                        .show()
                }
            }
            namedPlaylistList.addView(button)
        }
        playlistContainer.addView(namedPlaylistList)
        playlistContainer.addView(playlist)
        scroll.addView(playlistContainer)

        if (index in tracks.indices) {
            title.text = tracks[index].first
        }

        refreshPlaylist()
        loadArtwork()

        root.addView(
            label(
                "RETROAMP 1.7.0  •  MINHAS PLAYLISTS",
                silver, 10f
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

        val filter = IntentFilter(
            MusicService.UPDATE
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(receiver, filter)
        }

        receiverRegistered = true

        if (serviceRunning()) {
            command(MusicService.REFRESH)
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {}

            receiverRegistered = false
        }

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
            addCategory(
                Intent.CATEGORY_OPENABLE
            )
            type = "audio/*"

            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                true
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
                uris.add(
                    it.getItemAt(i).uri
                )
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
        updateLibraryStatus()

        if (serviceRunning()) {
            command(MusicService.REFRESH)
        }

        if (index == -1 && tracks.isNotEmpty()) {
            command(
                MusicService.PLAY_INDEX, 0
            )
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
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
            return
        }

        tracks.forEachIndexed { i, track ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                if (i == index) {
                    setBackgroundColor(
                        Color.rgb(47, 83, 55)
                    )
                }
            }

            val songLabel = label(
                "${i + 1}. ${track.first}",
                if (i == index) Color.WHITE else green,
                13f
            ).apply {
                typeface = Typeface.MONOSPACE
                setPadding(
                    dp(10), dp(9), dp(4), dp(9)
                )
                maxLines = 2

                setOnClickListener {
                    command(
                        MusicService.PLAY_INDEX, i
                    )
                }
            }

            row.addView(
                songLabel,
                LinearLayout.LayoutParams(
                    0, -2, 1f
                )
            )

            val removeButton = Button(this).apply {
                text = "✕"
                textSize = 17f
                isAllCaps = false
                setTextColor(
                    Color.rgb(255, 155, 155)
                )

                contentDescription =
                    "Remover ${track.first}"

                backgroundTintList =
                    ColorStateList.valueOf(
                        Color.rgb(55, 55, 60)
                    )

                setOnClickListener {
                    confirmRemove(i)
                }
            }

            row.addView(
                removeButton,
                LinearLayout.LayoutParams(
                    dp(48), dp(44)
                )
            )

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
        LinearLayout.LayoutParams(
            -1, -2
        )

    private fun dp(value: Int) =
        (
            value *
                resources.displayMetrics.density
            ).toInt()
}
