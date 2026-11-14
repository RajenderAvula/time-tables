package app.timetables

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * Location alarms that keep working when the app is closed. The page sends its location reminders;
 * Google Play services watches the places (battery-friendly) and wakes GeofenceReceiver on arrival or departure.
 */
object Geo {
    private const val PREFS = "geo"
    data class Fence(val id: String, val lat: Double, val lng: Double, val r: Float, val out: Boolean, val title: String, val body: String)

    fun hasPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun load(ctx: Context): List<Fence> {
        val out = mutableListOf<Fence>()
        try {
            val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fences", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Fence(
                    o.getString("id"), o.getDouble("lat"), o.getDouble("lng"), o.optDouble("r", 200.0).toFloat(),
                    o.optString("mode") == "out", o.optString("title", "Location alarm"), o.optString("body", "")
                )
            }
        } catch (e: Exception) { }
        return out
    }

    fun replaceAll(ctx: Context, json: String) {
        try { JSONArray(json) } catch (e: Exception) { return }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fences", json).apply()
        register(ctx)
    }

    private fun pi(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 2, Intent(ctx, GeofenceReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val client = try { LocationServices.getGeofencingClient(ctx) } catch (e: Throwable) { return }
        val pending = pi(ctx)
        try {
            client.removeGeofences(pending).addOnCompleteListener {
                val list = load(ctx).take(95)
                if (list.isEmpty() || !hasPermission(ctx)) return@addOnCompleteListener
                val fences = list.map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.lat, it.lng, maxOf(it.r, 100f))
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(if (it.out) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                }
                val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
                try { client.addGeofences(req, pending) } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    fun recordHit(ctx: Context, id: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = try { JSONArray(sp.getString("hits", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(id)
        sp.edit().putString("hits", a.toString()).apply()
    }

    fun takeHits(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("hits", "[]") ?: "[]"
        sp.edit().putString("hits", "[]").apply()
        return s
    }

    fun find(ctx: Context, id: String): Fence? = load(ctx).firstOrNull { it.id == id }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        ev.triggeringGeofences?.forEach { g ->
            val f = Geo.find(ctx, g.requestId) ?: return@forEach
            Geo.recordHit(ctx, f.id)
            Scheduler.ring(ctx, f.id, f.title, f.body.ifBlank { if (f.out) "You left this place" else "You arrived" })
        }
    }
}
