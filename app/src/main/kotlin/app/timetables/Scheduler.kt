package app.timetables

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** True while the activity is on screen; the page rings its own alarms then. */
object AppState {
    @Volatile var foreground = false
}

/**
 * Keeps a copy of every upcoming alarm, reminder, digest and Pomodoro end (sent by the page)
 * and wakes the phone for the next one with AlarmManager.setAlarmClock, which is exempt from
 * battery restrictions and works when the app is closed or the phone is idle.
 */
object Scheduler {
    const val CHANNEL = "alarms"
    const val ACTION_FIRE = "app.timetables.FIRE"
    const val ACTION_SNOOZE = "app.timetables.SNOOZE"
    const val ACTION_STOP = "app.timetables.STOP"
    const val ACTION_DONE = "app.timetables.DONE"
    const val RING_CHANNEL = "ringing"
    private const val PREFS = "sched"
    private const val KEY = "events"
    private const val SNOOZE_MS = 5 * 60_000L
    private const val STALE_MS = 6 * 3_600_000L

    data class Ev(val id: String, val t: Long, val title: String, val body: String, val x: String = "")

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Box alarms, reminders, daily digest and Pomodoro"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Silent channel for the ringing notification: the service plays the sound itself, on the alarm volume. */
    fun ensureRingChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(RING_CHANNEL) != null) return
        val ch = NotificationChannel(RING_CHANNEL, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm is ringing, with Stop and Snooze"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Box alarms, their snoozes and Pomodoro ring; digests, wrap-ups and reminders only notify. */
    private fun rings(id: String) = id.startsWith("a") || id.startsWith("z") || id.startsWith("g") || id == "pomo"

    /** Starts the ringing service (sound, vibration, full-screen alarm); falls back to a notification. */
    fun ring(ctx: Context, id: String, title: String, body: String) {
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AlarmService::class.java).putExtra("id", id).putExtra("title", title).putExtra("body", body)
            )
        } catch (e: Exception) {
            show(ctx, id, title, body, true)
        }
    }

    fun stopRinging(ctx: Context) {
        ctx.stopService(Intent(ctx, AlarmService::class.java))
        ctx.getSystemService(NotificationManager::class.java).cancel(AlarmService.NOTIF_ID)
    }

    private fun load(ctx: Context): MutableList<Ev> {
        val out = mutableListOf<Ev>()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Ev(o.getString("id"), o.getLong("t"), o.optString("title", "Alarm"), o.optString("body", ""), o.optString("x", ""))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun save(ctx: Context, list: List<Ev>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("t", it.t).put("title", it.title).put("body", it.body).put("x", it.x))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Called by the page whenever its schedule changes. */
    fun replaceAll(ctx: Context, json: String) {
        val list = mutableListOf<Ev>()
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list += Ev(
                    o.getString("id"), o.getLong("t"), o.optString("title", "Alarm"), o.optString("body", ""),
                    o.optJSONObject("x")?.toString() ?: ""
                )
            }
        } catch (e: Exception) { return }
        val done = doneSet(ctx)
        save(ctx, list.filter { grp(it) !in done })
        scheduleNext(ctx)
    }

    private fun grp(e: Ev): String = if (e.x.isEmpty()) "" else try { JSONObject(e.x).optString("grp") } catch (x: Exception) { "" }

    private fun doneSet(ctx: Context): Set<String> = try {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("done", "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    /** The user tapped Done on a call/message alert: drop every reminder for it and tell the page next time it opens. */
    fun markDone(ctx: Context, g: String) {
        if (g.isEmpty()) return
        val a = JSONArray(doneSet(ctx).toList() + g)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("done", a.toString()).apply()
        save(ctx, load(ctx).filter { grp(it) != g })
        ctx.getSystemService(NotificationManager::class.java).cancel(g.hashCode())
        scheduleNext(ctx)
    }

    fun takeDone(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("done", "[]") ?: "[]"
        sp.edit().putString("done", "[]").apply()
        return s
    }

    /** A scheduled call/message/app alert with a one-tap action and a Done button. */
    private fun showAct(ctx: Context, e: Ev) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val x = try { JSONObject(e.x) } catch (ex: Exception) { return }
        val g = x.optString("grp")
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(e.title)
            .setContentText(e.body.lineSequence().lastOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setContentIntent(open)
        Act.intent(ctx, x)?.let {
            b.addAction(0, Act.label(x.optString("k")), PendingIntent.getActivity(ctx, g.hashCode() xor 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        b.addAction(
            0, "Done",
            PendingIntent.getBroadcast(
                ctx, g.hashCode() xor 2,
                Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DONE).putExtra("grp", g),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        try { NotificationManagerCompat.from(ctx).notify(g.hashCode(), b.build()) } catch (se: SecurityException) { }
    }

    private fun firePi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePi(ctx)
        val next = load(ctx).minByOrNull { it.t }
        if (next == null) { am.cancel(pi); return }
        val show = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = maxOf(next.t, System.currentTimeMillis() + 1000)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    /** Shows everything that is due, drops it from the list, and arms the next alarm. */
    fun fireDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val all = load(ctx)
        val due = all.filter { it.t <= now + 1500 }
        save(ctx, all - due.toSet())
        if (!AppState.foreground) {
            val fresh = due.filter { now - it.t < STALE_MS }
            val done = doneSet(ctx)
            fresh.filter { it.x.isNotEmpty() && grp(it) !in done }.forEach { showAct(ctx, it) }
            val loud = fresh.filter { it.x.isEmpty() && rings(it.id) }
            fresh.filter { it.x.isEmpty() && !rings(it.id) }.forEach { show(ctx, it.id, it.title, it.body, true) }
            if (loud.isNotEmpty()) {
                val f = loud.first()
                val more = loud.drop(1).joinToString("; ") { it.title }
                ring(ctx, f.id, f.title, if (more.isBlank()) f.body else f.body + "\nAlso: " + more)
            }
        }
        scheduleNext(ctx)
    }

    fun snooze(ctx: Context, id: String, title: String, body: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        stopRinging(ctx)
        val list = load(ctx)
        list += Ev("z$id", System.currentTimeMillis() + SNOOZE_MS, title, body)
        save(ctx, list)
        scheduleNext(ctx)
    }

    fun show(ctx: Context, id: String, title: String, body: String, withSnooze: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (withSnooze) {
            val sn = PendingIntent.getBroadcast(
                ctx, id.hashCode(),
                Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SNOOZE)
                    .putExtra("id", id).putExtra("title", title).putExtra("body", body),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "Snooze 5 min", sn)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), b.build())
        } catch (e: SecurityException) { }
    }
}
