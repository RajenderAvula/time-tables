package app.timetables

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires due alarms, handles Snooze, and re-arms everything after a reboot, update or clock change. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_SNOOZE -> Scheduler.snooze(
                ctx,
                intent.getStringExtra("id") ?: return,
                intent.getStringExtra("title") ?: "Alarm",
                intent.getStringExtra("body") ?: ""
            )
            else -> Scheduler.fireDue(ctx) // FIRE, BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED
        }
    }
}
