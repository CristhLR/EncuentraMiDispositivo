package com.cristhlr.encuentramidispositivo.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RingService : Service() {
    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (player == null) startRinging()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_RING_DURATION_MS)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ring").apply {
            acquire(MAX_RING_DURATION_MS)
        }

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setDataSource(this@RingService, alarmUri)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Búsqueda de dispositivo",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) },
            )
        }

        val stopIntent = Intent(this, RingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Este dispositivo está siendo buscado")
            .setContentText("Sonando al volumen de alarma máximo")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Detener sonido", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        player?.runCatching { stop() }
        player?.release()
        player = null
        wakeLock?.takeIf { it.isHeld }?.release()

        previousAlarmVolume?.let { volume ->
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        previousAlarmVolume = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cristhlr.encuentramidispositivo.START_RING"
        const val ACTION_STOP = "com.cristhlr.encuentramidispositivo.STOP_RING"
        private const val CHANNEL_ID = "device_ring"
        private const val NOTIFICATION_ID = 901
        private const val MAX_RING_DURATION_MS = 5 * 60 * 1000L
    }
}

