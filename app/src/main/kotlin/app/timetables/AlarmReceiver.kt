package app.timetables

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires due alarms, handles Snooze, and re-arms everything after a reboot, update or clock change. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_STOP -> Scheduler.stopRinging(ctx)
            Scheduler.ACTION_DONE -> Scheduler.markDone(ctx, intent.getStringExtra("grp") ?: "")
            Scheduler.ACTION_SNOOZE -> Scheduler.snooze(
                ctx,
                intent.getStringExtra("id") ?: return,
                intent.getStringExtra("title") ?: "Alarm",
                intent.getStringExtra("body") ?: ""
            )
            else -> {
                if (intent.action != Scheduler.ACTION_FIRE) Geo.register(ctx) // reboot, update, clock change
                Scheduler.fireDue(ctx)
            } 
        }
    }
}
