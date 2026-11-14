package app.timetables

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

/**
 * Rings an alarm like a clock app: loops the alarm sound on the alarm volume, vibrates, wakes the
 * screen with a full-screen notification and offers Stop and Snooze. Runs as a foreground service so
 * the system keeps it alive while the app is closed.
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: "alarm"
        val title = intent?.getStringExtra("title") ?: "Alarm"
        val body = intent?.getStringExtra("body") ?: ""
        Scheduler.ensureRingChannel(this)
        val n = build(this, id, title, body)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        ring()
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 90_000)
        return START_NOT_STICKY
    }

    private fun ring() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wake?.isHeld != true) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timetables:ring").apply { acquire(95_000) }
            }
        } catch (e: Exception) { }
        if (player == null) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) { player = null }
        }
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 600, 400, 600, 400, 600, 1200)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoStop)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 7001

        fun build(ctx: Context, id: String, title: String, body: String): Notification {
            val open = PendingIntent.getActivity(
                ctx, 10,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("ring", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getBroadcast(
                ctx, 11,
                Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val snooze = PendingIntent.getBroadcast(
                ctx, id.hashCode(),
                Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_SNOOZE)
                    .putExtra("id", id).putExtra("title", title).putExtra("body", body),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, Scheduler.RING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(body.ifBlank { "Alarm" })
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .addAction(0, "Snooze 5 min", snooze)
                .build()
        }
    }
}
