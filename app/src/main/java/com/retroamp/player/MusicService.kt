
package com.retroamp.player

import android.app.*
import android.content.*
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaMetadata
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
    }

    private val songs = mutableListOf<Pair<String, Uri>>()
    private var player: MediaPlayer? = null
    private var ready = false
    private var currentIndex = -1
    private var shuffle = false
    private var repeat = false
    private var foreground = false
    private var started = false

    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (ready) {
                broadcastUpdate()
            }
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

    private fun currentTitle(): String =
        songs.getOrNull(currentIndex)?.first ?: "RetroAmp"

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

    private fun playIndex(i: Int, restore: Boolean = false) {
        loadSongs()
        if (i !in songs.indices) return

        val resumePosition =
            if (restore) prefs.getInt("saved_position", 0)
            else 0

        ready = false
        player?.release()
        player = null

        currentIndex = i

        prefs.edit()
            .putInt("saved_index", i)
            .putInt("saved_position", resumePosition)
            .apply()

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
                ready = false
                updateAll()
                true
            }

            player = p
            p.prepareAsync()
            updateAll()

        } catch (_: Exception) {
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
        } else {
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
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    currentTitle()
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    "RetroAmp by Matheusoblack"
                )
                .putLong(
                    MediaMetadata.METADATA_KEY_DURATION,
                    duration().toLong()
                )
                .build()
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

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle())
            .setContentText("RetroAmp by Matheusoblack")
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
            .build()
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
            putExtra(EXTRA_TITLE, currentTitle())
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
