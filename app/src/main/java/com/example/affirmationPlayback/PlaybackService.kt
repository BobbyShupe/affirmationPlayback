package com.example.affirmationPlayback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.affirmationPlayback.MainActivity.Companion.ACTION_PLAYBACK_STATE
import com.example.affirmationPlayback.MainActivity.Companion.EXTRA_CURRENT_INDEX
import com.example.affirmationPlayback.MainActivity.Companion.EXTRA_FILE_PATH
import com.example.affirmationPlayback.MainActivity.Companion.EXTRA_STATE
import com.example.affirmationPlayback.MainActivity.Companion.EXTRA_TOTAL_COUNT
import com.example.affirmationPlayback.MainActivity.Companion.EXTRA_TRACK_NAME
import com.example.affirmationPlayback.MainActivity.Companion.STATE_COMPLETED
import com.example.affirmationPlayback.MainActivity.Companion.STATE_PLAYING
import com.example.affirmationPlayback.MainActivity.Companion.STATE_STOPPED
import java.io.File

class PlaybackService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var playbackOrder = mutableListOf<String>()
    private var currentIndex = -1
    private var isPlaying = false
    private var delaySeconds = 0
    private var isShuffle = false

    companion object {
        private const val TAG = "AffirmPlaybackSvc"

        const val CHANNEL_ID = "affirmation_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.affirmationPlayback.ACTION_STOP_PLAYBACK"
        const val ACTION_START_PLAYLIST = "com.example.affirmationPlayback.ACTION_START_PLAYLIST"

        const val EXTRA_FILE_PATHS = "extra_file_paths"
        const val EXTRA_SHUFFLE = "extra_shuffle"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AffirmationApp::PlaybackLock")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }

            ACTION_START_PLAYLIST -> {
                val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: return START_NOT_STICKY
                if (paths.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification("Preparing..."))

                isShuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0)

                prepareAndPlayPlaylist(paths)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Affirmation Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for affirmation playback"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Affirmations")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun prepareAndPlayPlaylist(paths: List<String>) {
        playbackOrder.clear()
        playbackOrder.addAll(paths)
        if (isShuffle) playbackOrder.shuffle()

        if (playbackOrder.isEmpty()) {
            stopSelf()
            return
        }

        currentIndex = -1
        isPlaying = true
        playNext()
    }

    private fun playNext() {
        handler.removeCallbacksAndMessages(null)

        currentIndex++

        if (currentIndex >= playbackOrder.size || !isPlaying) {
            stopPlayback()
            return
        }

        val filePath = playbackOrder[currentIndex]
        val file = File(filePath)

        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "File missing: $filePath")
            playNext()
            return
        }

        // Update notification
        val trackName = file.nameWithoutExtension
        val notifText = if (playbackOrder.size > 1) {
            "Playing (${currentIndex + 1}/${playbackOrder.size}): $trackName"
        } else {
            "Playing: $trackName"
        }
        val notification = buildNotification(notifText)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

        // Broadcast to activity
        val stateIntent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_PLAYING)
            putExtra(EXTRA_TRACK_NAME, trackName)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(EXTRA_TOTAL_COUNT, playbackOrder.size)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent)

        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                setWakeMode(applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()

                setOnCompletionListener {
                    if (currentIndex + 1 < playbackOrder.size) {
                        val delayMs = if (delaySeconds > 0) delaySeconds * 1000L else 0L
                        wakeLock?.acquire(delayMs + 2000L)
                        handler.postDelayed({ playNext() }, delayMs)
                    } else {
                        // Last track finished
                        val doneIntent = Intent(ACTION_PLAYBACK_STATE).apply {
                            putExtra(EXTRA_STATE, STATE_COMPLETED)
                        }
                        LocalBroadcastManager.getInstance(this@PlaybackService).sendBroadcast(doneIntent)
                        stopPlayback()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what $extra")
                    playNext()
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Play failed: $filePath", e)
                playNext()
            }
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()

        isPlaying = false
        currentIndex = -1
        playbackOrder.clear()

        // Notify UI
        val stopIntent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.release()
        stopSelf()
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}