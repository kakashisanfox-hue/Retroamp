
package com.retroamp.player

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import org.json.JSONArray
import java.util.concurrent.Executors
import kotlin.random.Random

class MusicService : Service() {

    companion object {
        const val CHANNEL = "retroamp_music"
        const val NOTIFICATION_ID = 101

        const val PLAY_INDEX = "retroamp.PLAY_INDEX"
        const val PLAY = "retroamp.PLAY"
        const val PAUSE = "retroamp.PAUSE"
        const val TOGGLE = "retroamp.TOGGLE"
        const val NEXT = "retroamp.NEXT"
        const val PREVIOUS = "retroamp.PREVIOUS"
        const val STOP = "retroamp.STOP"
        const val SEEK = "retroamp.SEEK"
        const val REFRESH = "retroamp.REFRESH"
        const val UPDATE = "retroamp.UPDATE"

        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_READY = "ready"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
    }

    private val songs = mutableListOf<Pair<String, Uri>>()

    private var player: MediaPlayer? = null
    private var ready = false
    private var preparing = false
    private var currentIndex = -1

    private var shuffle = false
    private var repeat = false

    private var foreground = false
    private var started = false
    private var destroyed = false

    // Cada nova tentativa de reprodução recebe
    // uma identificação para ignorar callbacks antigos.
    private var generation = 0

    private var songTitle = "RetroAmp"
    private var songArtist = "Artista desconhecido"
    private var songAlbum = "Álbum desconhecido"
    private var albumArt: Bitmap? = null

    private lateinit var session: MediaSession

    private val handler = Handler(Looper.getMainLooper())

    // Metadados e capas são processados fora
    // da thread principal do Android.
    private val metadataExecutor =
        Executors.newSingleThreadExecutor()

    private val prefs
        get() = getSharedPreferences(
            "MainActivity",
            MODE_PRIVATE
        )

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return

            if (ready) {
                broadcastUpdate()
            }

            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        loadSongs()

        session = MediaSession(this, "RetroAmp").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    handler.post { resume() }
                }

                override fun onPause() {
                    handler.post { pause() }
                }

                override fun onSkipToNext() {
                    handler.post { next() }
                }

                override fun onSkipToPrevious() {
                    handler.post { previous() }
                }

                override fun onStop() {
                    handler.post { stop() }
                }

                override fun onSeekTo(pos: Long) {
                    handler.post {
                        seekTo(
                            pos.coerceIn(
                                0L,
                                Int.MAX_VALUE.toLong()
                            ).toInt()
                        )
                    }
                }
            })

            isActive = true
        }

        updateSession()
        handler.post(ticker)
    }

    private fun loadSongs() {
        val loaded = mutableListOf<Pair<String, Uri>>()

        try {
            val array = JSONArray(
                prefs.getString(
                    "saved_playlist",
                    "[]"
                ) ?: "[]"
            )

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                loaded.add(
                    item.getString("name") to
                        Uri.parse(
                            item.getString("uri")
                        )
                )
            }

            songs.clear()
            songs.addAll(loaded)

        } catch (_: Exception) {
            // Mantém a lista anterior se o JSON
            // estiver temporariamente inválido.
        }

        shuffle = prefs.getBoolean(
            "shuffle", false
        )

        repeat = prefs.getBoolean(
            "repeat", false
        )

        if (currentIndex == -1) {
            currentIndex = prefs.getInt(
                "saved_index", -1
            )
        }
    }

    private fun isPlaying(): Boolean {
        return try {
            ready && player?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    private fun position(): Int {
        return try {
            if (ready) {
                player?.currentPosition ?: 0
            } else {
                prefs.getInt(
                    "saved_position", 0
                )
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun duration(): Int {
        return try {
            if (ready) {
                player?.duration ?: 0
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun savePosition() {
        prefs.edit()
            .putInt(
                "saved_position",
                position()
            )
            .putInt(
                "saved_index",
                currentIndex
            )
            .apply()
    }

    private fun releasePlayer() {
        val oldPlayer = player

        player = null
        ready = false
        preparing = false

        if (oldPlayer != null) {
            try {
                oldPlayer.setOnCompletionListener(null)
                oldPlayer.setOnPreparedListener(null)
                oldPlayer.setOnErrorListener(null)
            } catch (_: Exception) {}

            try {
                oldPlayer.reset()
            } catch (_: Exception) {}

            try {
                oldPlayer.release()
            } catch (_: Exception) {}
        }
    }

    private fun fallbackArtwork(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            320,
            320,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        canvas.drawColor(
            Color.rgb(7, 24, 14)
        )

        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.rgb(108, 255, 85)
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }

        canvas.drawRoundRect(
            18f, 18f,
            302f, 302f,
            18f, 18f,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(
            "monospace",
            Typeface.BOLD
        )
        paint.textAlign = Paint.Align.CENTER

        paint.textSize = 38f
        canvas.drawText(
            "RETROAMP",
            160f,
            132f,
            paint
        )

        paint.textSize = 82f
        canvas.drawText(
            "♫",
            160f,
            229f,
            paint
        )

        paint.textSize = 16f
        canvas.drawText(
            "BY MATHEUSOBLACK",
            160f,
            277f,
            paint
        )

        return bitmap
    }

    private data class TrackMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val artwork: Bitmap?
    )

    private fun readMetadata(
        uri: Uri,
        fileName: String
    ): TrackMetadata {

        var title = fileName.substringBeforeLast(
            ".", fileName
        )

        var artist = "Artista desconhecido"
        var album = "Álbum desconhecido"
        var artwork: Bitmap? = null

        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(this, uri)

            title = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            )?.takeIf {
                it.isNotBlank()
            } ?: title

            artist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ARTIST
            )?.takeIf {
                it.isNotBlank()
            } ?: artist

            album = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUM
            )?.takeIf {
                it.isNotBlank()
            } ?: album

            val bytes = retriever.embeddedPicture

            if (bytes != null) {
                val bounds =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    bounds
                )

                val maxSide = maxOf(
                    bounds.outWidth,
                    bounds.outHeight
                )

                val sample = (
                    maxSide / 512
                ).coerceAtLeast(1)

                artwork = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }
                )
            }

        } catch (_: Exception) {
            // Metadados não devem impedir
            // a reprodução da música.
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        return TrackMetadata(
            title,
            artist,
            album,
            artwork
        )
    }

    private fun loadMetadataAsync(
        uri: Uri,
        fileName: String,
        requestGeneration: Int
    ) {
        metadataExecutor.execute {
            val result = readMetadata(
                uri,
                fileName
            )

            handler.post {
                if (
                    destroyed ||
                    requestGeneration != generation
                ) {
                    return@post
                }

                songTitle = result.title
                songArtist = result.artist
                songAlbum = result.album
                albumArt = result.artwork

                updateAll()
            }
        }
    }

    private fun playIndex(
        i: Int,
        restore: Boolean = false
    ) {
        loadSongs()

        if (i !in songs.indices) {
            return
        }

        val song = songs[i]

        val resumePosition = if (restore) {
            prefs.getInt(
                "saved_position", 0
            )
        } else {
            0
        }

        generation++
        val requestGeneration = generation

        // Libera o player antigo antes de criar outro.
        releasePlayer()

        currentIndex = i
        preparing = true

        songTitle = song.first.substringBeforeLast(
            ".", song.first
        )

        songArtist = "Artista desconhecido"
        songAlbum = "Álbum desconhecido"
        albumArt = null

        prefs.edit()
            .putInt("saved_index", i)
            .putInt(
                "saved_position",
                resumePosition
            )
            .apply()

        // Leitura de capa e tags em segundo plano.
        loadMetadataAsync(
            song.second,
            song.first,
            requestGeneration
        )

        try {
            val p = MediaPlayer()

            player = p

            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )

            p.setOnPreparedListener { prepared ->
                if (
                    destroyed ||
                    requestGeneration != generation ||
                    player !== prepared
                ) {
                    return@setOnPreparedListener
                }

                preparing = false
                ready = true

                try {
                    if (
                        restore &&
                        resumePosition > 0
                    ) {
                        val safePosition =
                            resumePosition.coerceIn(
                                0,
                                (
                                    prepared.duration - 1000
                                ).coerceAtLeast(0)
                            )

                        prepared.seekTo(safePosition)
                    }

                    prepared.start()
                    updateAll()

                } catch (_: Exception) {
                    handlePlaybackError(
                        requestGeneration
                    )
                }
            }

            p.setOnCompletionListener { completed ->
                if (
                    destroyed ||
                    requestGeneration != generation ||
                    player !== completed
                ) {
                    return@setOnCompletionListener
                }

                // Evita substituir o MediaPlayer
                // dentro do próprio callback de término.
                handler.post {
                    if (
                        !destroyed &&
                        requestGeneration == generation &&
                        player === completed
                    ) {
                        if (repeat) {
                            playIndex(currentIndex)
                        } else {
                            next()
                        }
                    }
                }
            }

            p.setOnErrorListener { failed, _, _ ->
                if (
                    !destroyed &&
                    requestGeneration == generation &&
                    player === failed
                ) {
                    handler.post {
                        handlePlaybackError(
                            requestGeneration
                        )
                    }
                }

                true
            }

            p.setDataSource(
                this,
                song.second
            )

            p.prepareAsync()
            updateAll()

        } catch (_: Exception) {
            handlePlaybackError(
                requestGeneration
            )
        }
    }

    private fun handlePlaybackError(
        requestGeneration: Int
    ) {
        if (
            destroyed ||
            requestGeneration != generation
        ) {
            return
        }

        generation++
        releasePlayer()

        prefs.edit()
            .putInt("saved_position", 0)
            .apply()

        updateAll()

        // Um arquivo inválido não deve
        // provocar um ciclo infinito de tentativas.
        // A reprodução fica parada para o usuário
        // escolher outra música.
    }

    private fun resume() {
        if (ready) {
            try {
                player?.start()
            } catch (_: Exception) {}

            updateAll()

        } else if (!preparing) {
            loadSongs()

            val i = if (
                currentIndex in songs.indices
            ) {
                currentIndex
            } else {
                0
            }

            if (i in songs.indices) {
                playIndex(i, true)
            }
        }
    }

    private fun pause() {
        if (ready) {
            try {
                player?.pause()
            } catch (_: Exception) {}
        }

        savePosition()
        updateAll()
    }

    private fun stop() {
        // Cancela preparações pendentes também.
        generation++

        releasePlayer()

        prefs.edit()
            .putInt(
                "saved_position", 0
            )
            .apply()

        updateAll()
    }

    private fun next() {
        loadSongs()

        if (songs.isEmpty()) {
            stop()
            return
        }

        val i = if (
            shuffle &&
            songs.size > 1
        ) {
            songs.indices.filter {
                it != currentIndex
            }.random()
        } else {
            currentIndex + 1
        }

        if (i in songs.indices) {
            playIndex(i)
        } else {
            stop()
        }
    }

    private fun previous() {
        loadSongs()

        if (songs.isEmpty()) {
            stop()
            return
        }

        val i = if (
            shuffle &&
            songs.size > 1
        ) {
            songs.indices.filter {
                it != currentIndex
            }.random()
        } else {
            currentIndex - 1
        }

        if (i in songs.indices) {
            playIndex(i)
        } else {
            seekTo(0)
        }
    }

    private fun seekTo(ms: Int) {
        if (!ready) return

        try {
            player?.seekTo(
                ms.coerceIn(
                    0,
                    duration()
                )
            )

            savePosition()
            updateAll()

        } catch (_: Exception) {}
    }

    private fun updateSession() {
        val metadata = MediaMetadata.Builder()
            .putString(
                MediaMetadata.METADATA_KEY_TITLE,
                songTitle
            )
            .putString(
                MediaMetadata.METADATA_KEY_ARTIST,
                songArtist
            )
            .putString(
                MediaMetadata.METADATA_KEY_ALBUM,
                songAlbum
            )
            .putLong(
                MediaMetadata.METADATA_KEY_DURATION,
                duration().toLong()
            )

        albumArt?.let {
            metadata.putBitmap(
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                it
            )
        }

        session.setMetadata(
            metadata.build()
        )

        val playing = isPlaying()

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) {
                        PlaybackState.STATE_PLAYING
                    } else {
                        PlaybackState.STATE_PAUSED
                    },
                    position().toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL,
            "RetroAmp Music",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                "Controles de música do RetroAmp"

            setShowBadge(false)
        }

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    private fun actionPending(
        action: String
    ): PendingIntent {
        val intent = Intent(
            this,
            MusicService::class.java
        ).apply {
            this.action = action
        }

        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notification(): Notification {
        val openIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openPending =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val playing = isPlaying()

        val builder =
            Notification.Builder(
                this,
                CHANNEL
            )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setContentTitle(songTitle)
                .setContentText(songArtist)
                .setSubText(songAlbum)
                .setContentIntent(openPending)
                .setVisibility(
                    Notification.VISIBILITY_PUBLIC
                )
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        "Anterior",
                        actionPending(PREVIOUS)
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        if (playing) {
                            android.R.drawable.ic_media_pause
                        } else {
                            android.R.drawable.ic_media_play
                        },
                        if (playing) {
                            "Pausar"
                        } else {
                            "Reproduzir"
                        },
                        actionPending(
                            if (playing) PAUSE else PLAY
                        )
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        "Próxima",
                        actionPending(NEXT)
                    ).build()
                )
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(
                            session.sessionToken
                        )
                        .setShowActionsInCompactView(
                            0, 1, 2
                        )
                )

        albumArt?.let {
            builder.setLargeIcon(it)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val n = notification()

        if (isPlaying()) {
            startForeground(
                NOTIFICATION_ID,
                n
            )

            foreground = true

        } else {
            if (foreground) {
                stopForeground(
                    STOP_FOREGROUND_DETACH
                )

                foreground = false
            }

            getSystemService(
                NotificationManager::class.java
            ).notify(
                NOTIFICATION_ID,
                n
            )
        }
    }

    private fun broadcastUpdate() {
        if (destroyed) return

        val intent = Intent(UPDATE).apply {
            setPackage(packageName)

            putExtra(
                EXTRA_INDEX,
                currentIndex
            )

            putExtra(
                EXTRA_POSITION,
                position()
            )

            putExtra(
                EXTRA_DURATION,
                duration()
            )

            putExtra(
                EXTRA_PLAYING,
                isPlaying()
            )

            putExtra(
                EXTRA_READY,
                ready
            )

            putExtra(
                EXTRA_TITLE,
                songTitle
            )

            putExtra(
                EXTRA_ARTIST,
                songArtist
            )

            putExtra(
                EXTRA_ALBUM,
                songAlbum
            )
        }

        sendBroadcast(intent)
    }

    private fun updateAll() {
        if (destroyed) return

        updateSession()
        updateNotification()
        broadcastUpdate()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!started) {
            startForeground(
                NOTIFICATION_ID,
                notification()
            )

            foreground = true
            started = true
        }

        when (intent?.action) {

            PLAY_INDEX -> {
                val i = intent.getIntExtra(
                    EXTRA_INDEX, -1
                )

                if (i >= 0) {
                    playIndex(i)
                }
            }

            PLAY -> resume()

            PAUSE -> pause()

            TOGGLE -> {
                if (isPlaying()) {
                    pause()
                } else {
                    resume()
                }
            }

            NEXT -> next()

            PREVIOUS -> previous()

            STOP -> stop()

            SEEK -> {
                seekTo(
                    intent.getIntExtra(
                        EXTRA_POSITION, 0
                    )
                )
            }

            REFRESH -> {
                loadSongs()
                updateAll()
            }

            else -> updateAll()
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    override fun onDestroy() {
        savePosition()

        destroyed = true
        generation++

        handler.removeCallbacksAndMessages(null)

        releasePlayer()

        metadataExecutor.shutdownNow()

        if (::session.isInitialized) {
            session.isActive = false
            session.release()
        }

        super.onDestroy()
    }
}
