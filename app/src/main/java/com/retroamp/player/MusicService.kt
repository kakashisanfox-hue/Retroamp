
package com.retroamp.player

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import org.json.JSONArray

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
    private var currentIndex = -1
    private var shuffle = false
    private var repeat = false
    private var foreground = false
    private var started = false
    private var preparing = false

    private var songTitle = "RetroAmp"
    private var songArtist = "Artista desconhecido"
    private var songAlbum = "Álbum desconhecido"
    private var albumArt: Bitmap? = null

    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (ready) broadcastUpdate()
            handler.postDelayed(this, 500)
        }
    }

    private val prefs
        get() = getSharedPreferences("MainActivity", MODE_PRIVATE)

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
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onStop() = stop()

                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }
            })

            isActive = true
        }

        updateSession()
        handler.post(ticker)
    }

    private fun loadSongs() {
        songs.clear()

        try {
            val array = JSONArray(
                prefs.getString("saved_playlist", "[]") ?: "[]"
            )

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                songs.add(
                    item.getString("name") to
                        Uri.parse(item.getString("uri"))
                )
            }
        } catch (_: Exception) {}

        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getBoolean("repeat", false)

        if (currentIndex == -1) {
            currentIndex = prefs.getInt("saved_index", -1)
        }
    }

    private fun isPlaying(): Boolean = try {
        ready && player?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    private fun position(): Int = try {
        if (ready) player?.currentPosition ?: 0
        else prefs.getInt("saved_position", 0)
    } catch (_: Exception) {
        0
    }

    private fun duration(): Int = try {
        if (ready) player?.duration ?: 0 else 0
    } catch (_: Exception) {
        0
    }

    private fun savePosition() {
        prefs.edit()
            .putInt("saved_position", position())
            .putInt("saved_index", currentIndex)
            .apply()
    }

    private fun fallbackArtwork(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            320, 320, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(7, 24, 14))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(108, 255, 85)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        canvas.drawRoundRect(
            18f, 18f, 302f, 302f, 18f, 18f, paint
        )

        paint.style = Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.create(
            "monospace", android.graphics.Typeface.BOLD
        )
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 38f
        canvas.drawText("RETROAMP", 160f, 132f, paint)

        paint.textSize = 82f
        canvas.drawText("♫", 160f, 229f, paint)

        paint.textSize = 16f
        canvas.drawText(
            "BY MATHEUSOBLACK", 160f, 277f, paint
        )

        return bitmap
    }

    private fun readMetadata(uri: Uri, fileName: String) {
        songTitle = fileName.substringBeforeLast(
            ".", fileName
        )
        songArtist = "Artista desconhecido"
        songAlbum = "Álbum desconhecido"
        albumArt = null

        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(this, uri)

            songTitle = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            )?.takeIf { it.isNotBlank() } ?: songTitle

            songArtist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ARTIST
            )?.takeIf { it.isNotBlank() }
                ?: "Artista desconhecido"

            songAlbum = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUM
            )?.takeIf { it.isNotBlank() }
                ?: "Álbum desconhecido"

            val bytes = retriever.embeddedPicture

            if (bytes != null) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, options
                )

                val maxSide = maxOf(
                    options.outWidth, options.outHeight
                )
                val sample = (maxSide / 512).coerceAtLeast(1)

                val decodeOptions =
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }

                albumArt = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, decodeOptions
                )
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        if (albumArt == null) {
            albumArt = fallbackArtwork()
        }
    }

    private fun playIndex(i: Int, restore: Boolean = false) {
        loadSongs()
        if (i !in songs.indices) return

        val resumePosition =
            if (restore) prefs.getInt("saved_position", 0)
            else 0

        ready = false
        preparing = true
        player?.release()
        player = null

        currentIndex = i

        prefs.edit()
            .putInt("saved_index", i)
            .putInt("saved_position", resumePosition)
            .apply()

        readMetadata(songs[i].second, songs[i].first)

        try {
            val p = MediaPlayer()

            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )

            p.setDataSource(this, songs[i].second)

            p.setOnPreparedListener {
                preparing = false
                ready = true

                if (restore && resumePosition > 0) {
                    val safePosition = resumePosition.coerceIn(
                        0,
                        (it.duration - 1000).coerceAtLeast(0)
                    )
                    it.seekTo(safePosition)
                }

                it.start()
                updateAll()
            }

            p.setOnCompletionListener {
                if (repeat) {
                    playIndex(currentIndex)
                } else {
                    next()
                }
            }

            p.setOnErrorListener { _, _, _ ->
                preparing = false
                ready = false
                updateAll()
                true
            }

            player = p
            p.prepareAsync()
            updateAll()

        } catch (_: Exception) {
            preparing = false
            ready = false
            player?.release()
            player = null
            updateAll()
        }
    }

    private fun resume() {
        if (ready) {
            try {
                player?.start()
            } catch (_: Exception) {}
            updateAll()
        } else if (!preparing) {
            loadSongs()
            val i = if (currentIndex in songs.indices) {
                currentIndex
            } else {
                0
            }
            playIndex(i, true)
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
        if (ready) {
            try {
                player?.pause()
                player?.seekTo(0)
            } catch (_: Exception) {}
        }

        prefs.edit().putInt("saved_position", 0).apply()
        updateAll()
    }

    private fun next() {
        loadSongs()
        if (songs.isEmpty()) return

        val i = if (shuffle && songs.size > 1) {
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
        if (songs.isEmpty()) return

        val i = if (shuffle && songs.size > 1) {
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
            player?.seekTo(ms.coerceIn(0, duration()))
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

        session.setMetadata(metadata.build())

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
                    if (playing) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED,
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
            description = "Controles de música do RetroAmp"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun actionPending(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
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
            this, MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val playing = isPlaying()

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(songTitle)
            .setContentText(songArtist)
            .setSubText(songAlbum)
            .setContentIntent(openPending)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
                    if (playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (playing) "Pausar" else "Reproduzir",
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
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        albumArt?.let {
            builder.setLargeIcon(it)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val n = notification()

        if (isPlaying()) {
            startForeground(NOTIFICATION_ID, n)
            foreground = true
        } else {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
            }

            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, n)
        }
    }

    private fun broadcastUpdate() {
        val intent = Intent(UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_INDEX, currentIndex)
            putExtra(EXTRA_POSITION, position())
            putExtra(EXTRA_DURATION, duration())
            putExtra(EXTRA_PLAYING, isPlaying())
            putExtra(EXTRA_READY, ready)
            putExtra(EXTRA_TITLE, songTitle)
            putExtra(EXTRA_ARTIST, songArtist)
            putExtra(EXTRA_ALBUM, songAlbum)
        }

        sendBroadcast(intent)
    }

    private fun updateAll() {
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
                val i = intent.getIntExtra(EXTRA_INDEX, -1)
                if (i >= 0) playIndex(i)
            }

            PLAY -> resume()
            PAUSE -> pause()

            TOGGLE -> {
                if (isPlaying()) pause() else resume()
            }

            NEXT -> next()
            PREVIOUS -> previous()
            STOP -> stop()

            SEEK -> seekTo(
                intent.getIntExtra(EXTRA_POSITION, 0)
            )

            REFRESH -> {
                loadSongs()
                updateAll()
            }

            else -> updateAll()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePosition()
        handler.removeCallbacks(ticker)
        ready = false
        player?.release()
        player = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
