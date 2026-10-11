
package com.retroamp.player

import android.app.*
import android.content.*
import android.graphics.Color
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import org.json.JSONArray

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "retroamp_music"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY = "retroamp.PLAY"
        const val ACTION_PAUSE = "retroamp.PAUSE"
        const val ACTION_NEXT = "retroamp.NEXT"
        const val ACTION_PREVIOUS = "retroamp.PREVIOUS"
        const val ACTION_STOP = "retroamp.STOP"
        const val ACTION_OPEN = "retroamp.OPEN"
        const val ACTION_TOGGLE = "retroamp.TOGGLE"

        const val ACTION_UPDATE = "retroamp.UPDATE"
    }

    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession

    private val songs = mutableListOf<Pair<String, Uri>>()
    private var currentIndex = -1
    private var ready = false
    private var shuffle = false
    private var repeat = false

    override fun onCreate() {
        super.onCreate()

        createChannel()
        loadSongs()

        session = MediaSession(this, "RetroAmp").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    resumeMusic()
                }

                override fun onPause() {
                    pauseMusic()
                }

                override fun onSkipToNext() {
                    nextSong()
                }

                override fun onSkipToPrevious() {
                    previousSong()
                }

                override fun onStop() {
                    stopMusic()
                }

                override fun onSeekTo(pos: Long) {
                    seekMusic(pos.toInt())
                }
            })

            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            isActive = true
        }

        updateSession()
    }

    private fun loadSongs() {
        songs.clear()

        val prefs = getSharedPreferences(
            "MainActivity", MODE_PRIVATE
        )

        val saved = prefs.getString(
            "saved_playlist", "[]"
        ) ?: "[]"

        try {
            val array = JSONArray(saved)

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                songs.add(
                    item.getString("name") to
                        Uri.parse(item.getString("uri"))
                )
            }
        } catch (_: Exception) {}

        currentIndex = prefs.getInt("saved_index", -1)
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getBoolean("repeat", false)
    }

    private fun currentTitle(): String {
        return songs.getOrNull(currentIndex)?.first
            ?: "RetroAmp"
    }

    private fun isPlaying(): Boolean {
        return try {
            ready && player?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    private fun playSong(index: Int) {
        loadSongs()

        if (index !in songs.indices) return

        ready = false

        player?.release()
        player = null

        currentIndex = index

        getSharedPreferences(
            "MainActivity", MODE_PRIVATE
        ).edit()
            .putInt("saved_index", index)
            .putInt("saved_position", 0)
            .apply()

        try {
            val mediaPlayer = MediaPlayer()

            mediaPlayer.setDataSource(
                this, songs[index].second
            )

            mediaPlayer.setOnPreparedListener {
                ready = true
                it.start()
                updateSession()
                updateNotification()
                broadcastUpdate()
            }

            mediaPlayer.setOnCompletionListener {
                if (repeat) {
                    playSong(currentIndex)
                } else {
                    nextSong()
                }
            }

            mediaPlayer.setOnErrorListener { _, _, _ ->
                ready = false
                updateSession()
                updateNotification()
                broadcastUpdate()
                true
            }

            player = mediaPlayer
            mediaPlayer.prepareAsync()

            updateSession()
            updateNotification()
            broadcastUpdate()

        } catch (_: Exception) {
            ready = false
            player?.release()
            player = null
            updateSession()
            updateNotification()
        }
    }

    private fun resumeMusic() {
        if (ready) {
            try {
                player?.start()
            } catch (_: Exception) {}
        } else {
            loadSongs()

            if (currentIndex in songs.indices) {
                playSong(currentIndex)
                return
            }
        }

        updateSession()
        updateNotification()
        broadcastUpdate()
    }

    private fun pauseMusic() {
        if (ready) {
            try {
                player?.pause()
            } catch (_: Exception) {}
        }

        savePosition()
        updateSession()
        updateNotification()
        broadcastUpdate()
    }

    private fun stopMusic() {
        if (ready) {
            try {
                player?.pause()
                player?.seekTo(0)
            } catch (_: Exception) {}
        }

        updateSession()
        updateNotification()
        broadcastUpdate()
    }

    private fun nextSong() {
        loadSongs()

        if (songs.isEmpty()) return

        val next = if (shuffle && songs.size > 1) {
            songs.indices
                .filter { it != currentIndex }
                .random()
        } else {
            currentIndex + 1
        }

        if (next in songs.indices) {
            playSong(next)
        } else {
            stopMusic()
        }
    }

    private fun previousSong() {
        loadSongs()

        if (songs.isEmpty()) return

        val previous = if (shuffle && songs.size > 1) {
            songs.indices
                .filter { it != currentIndex }
                .random()
        } else {
            currentIndex - 1
        }

        if (previous in songs.indices) {
            playSong(previous)
        } else if (ready) {
            seekMusic(0)
        }
    }

    private fun seekMusic(position: Int) {
        if (!ready) return

        try {
            player?.seekTo(position)
            updateSession()
            broadcastUpdate()
        } catch (_: Exception) {}
    }

    private fun savePosition() {
        val position = try {
            if (ready) player?.currentPosition ?: 0
            else 0
        } catch (_: Exception) {
            0
        }

        getSharedPreferences(
            "MainActivity", MODE_PRIVATE
        ).edit()
            .putInt("saved_position", position)
            .apply()
    }

    private fun updateSession() {
        val duration = try {
            if (ready) player?.duration?.toLong() ?: 0L
            else 0L
        } catch (_: Exception) {
            0L
        }

        val position = try {
            if (ready) player?.currentPosition?.toLong() ?: 0L
            else 0L
        } catch (_: Exception) {
            0L
        }

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
                    duration
                )
                .build()
        )

        val state = if (isPlaying()) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }

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
                    state,
                    position,
                    if (isPlaying()) 1f else 0f
                )
                .build()
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RetroAmp Music",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controles de reprodução de música"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun actionIntent(action: String): PendingIntent {
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

    private fun buildNotification(): Notification {
        val openIntent = Intent(
            this, MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val playing = isPlaying()

        val previousAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Anterior",
            actionIntent(ACTION_PREVIOUS)
        ).build()

        val playAction = Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
            if (playing) "Pausar" else "Reproduzir",
            actionIntent(
                if (playing) ACTION_PAUSE else ACTION_PLAY
            )
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Próxima",
            actionIntent(ACTION_NEXT)
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle())
            .setContentText("RetroAmp by Matheusoblack")
            .setContentIntent(openPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(previousAction)
            .addAction(playAction)
            .addAction(nextAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()

        if (isPlaying()) {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        } else {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }

            getSystemService(
                NotificationManager::class.java
            ).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastUpdate() {
        val intent = Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra("index", currentIndex)
            putExtra("playing", isPlaying())
        }

        sendBroadcast(intent)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_OPEN -> {
                val selectedIndex = intent.getIntExtra(
                    "index", -1
                )

                if (selectedIndex >= 0) {
                    playSong(selectedIndex)
                }
            }

            ACTION_PLAY -> resumeMusic()
            ACTION_PAUSE -> pauseMusic()
            ACTION_NEXT -> nextSong()
            ACTION_PREVIOUS -> previousSong()
            ACTION_STOP -> stopMusic()

            ACTION_TOGGLE -> {
                if (isPlaying()) pauseMusic()
                else resumeMusic()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePosition()
        ready = false
        player?.release()
        player = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
