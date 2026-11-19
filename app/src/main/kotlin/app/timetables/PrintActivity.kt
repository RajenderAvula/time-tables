package app.timetables

import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Holds the page to print, so a large document does not have to travel inside an Intent. */
object PrintHolder {
    var title: String = "Timetable"
    var html: String = ""
    var autoPdf: Boolean = false
}

/**
 * A separate screen just for printing. The document is shown in a normal, visible WebView
 * (not inside the main app page), and printed or saved as a PDF from here.
 */
class PrintActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = PrintHolder.title.ifBlank { "Timetable" }
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#e9e9ee")) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }
        val title = TextView(this).apply { text = "Print preview"; setTextColor(Color.BLACK); textSize = 16f }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bPrint = Button(this).apply { text = "Print"; isAllCaps = false }
        val bPdf = Button(this).apply { text = "Save PDF"; isAllCaps = false }
        val bClose = Button(this).apply { text = "Close"; isAllCaps = false }
        bar.addView(bPdf); bar.addView(bPrint); bar.addView(bClose)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.setBackgroundColor(Color.WHITE)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        bClose.setOnClickListener { finish() }
        bPrint.setOnClickListener {
            if (!loaded) { toast("Still preparing the page"); return@setOnClickListener }
            try {
                val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
                val attrs = android.print.PrintAttributes.Builder()
                    .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4.asLandscape())
                    .build()
                pm.print(name, web.createPrintDocumentAdapter(name), attrs)
            } catch (e: Exception) {
                toast("Printing is not available here. Use Save PDF.")
            }
        }
        bPdf.setOnClickListener { savePdf(name) }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (loaded) return
                loaded = true
                if (PrintHolder.autoPdf) { PrintHolder.autoPdf = false; view.postDelayed({ savePdf(name) }, 500) }
            }
        }
        web.loadDataWithBaseURL(null, PrintHolder.html, "text/html", "utf-8", null)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun savePdf(name: String) {
        if (!loaded) { toast("Still preparing the page"); return }
        val tmp = File(cacheDir, "print.pdf")
        try {
            android.print.PdfExporter.export(web.createPrintDocumentAdapter(name), tmp) { ok ->
                runOnUiThread {
                    val where = if (ok) try { commit(tmp, "$name.pdf") } catch (e: Exception) { null } else null
                    tmp.delete()
                    toast(if (where != null) "Saved $name.pdf to $where" else "Could not create the PDF")
                }
            }
        } catch (e: Exception) {
            toast("Could not create the PDF")
        }
    }

    private fun commit(src: File, name: String): String? {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("pdf") ?: "application/pdf"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Time Tables")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            val out = contentResolver.openOutputStream(uri) ?: return null
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            "Downloads/Time Tables"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            src.copyTo(File(dir, safe), overwrite = true)
            dir.absolutePath
        }
    }

    override fun onDestroy() {
        try { web.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }
}
