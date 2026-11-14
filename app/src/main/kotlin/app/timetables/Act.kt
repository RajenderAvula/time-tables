package app.timetables

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Builds the intent for a scheduled call, text, WhatsApp message or app launch. The last tap is the user's: Android lets no app do these unattended. */
object Act {
    fun label(k: String) = when (k) { "call" -> "Call"; "sms" -> "Text"; "wa" -> "WhatsApp"; else -> "Open" }

    fun intent(ctx: Context, x: JSONObject): Intent? {
        val k = x.optString("k")
        val to = x.optString("to")
        val text = x.optString("text")
        val pm = ctx.packageManager
        val i: Intent? = when (k) {
            "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(to)))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to))).putExtra("sms_body", text)
            "wa" -> {
                val v = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + x.optString("wa") + "?text=" + Uri.encode(text)))
                listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { v.setPackage(it) }
                v
            }
            "app" -> pm.getLaunchIntentForPackage(x.optString("pkg"))
            else -> null
        }
        return i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
