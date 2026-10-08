package com.pogo.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/**
 * Full Pokédex dashboard (WebView). Minimizing hands the screen over to
 * FloatingOverlayService; this activity then stays alive in the background only
 * to evaluate the service's OCR results against the Pokédex data in the WebView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var webViewGone = false

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_MEDIA_PROJECTION = 102
        private const val PREFS = "pogo_overlay"
        private const val PREF_CAPTURE_EXPLAINED = "capture_explained_v"
        private const val CAPTURE_EXPLAINER_VERSION = 2   // bump whenever the explainer text changes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.appTitle).text = "${getString(R.string.app_name)} v$versionName"
        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener { minimizeToPill() }
        // Pill style: narrow pill on the side, or a strip across the top. Remembered on the phone.
        val styleBtn = findViewById<Button>(R.id.btnPillStyle)
        val overlayPrefs = getSharedPreferences(FloatingOverlayService.PREFS, MODE_PRIVATE)
        fun showStyle() {
            val bar = overlayPrefs.getString(FloatingOverlayService.PREF_PILL_STYLE, "side") == "bar"
            styleBtn.text = getString(if (bar) R.string.pill_style_bar else R.string.pill_style_side)
        }
        showStyle()
        styleBtn.setOnClickListener {
            val bar = overlayPrefs.getString(FloatingOverlayService.PREF_PILL_STYLE, "side") == "bar"
            overlayPrefs.edit().putString(FloatingOverlayService.PREF_PILL_STYLE, if (bar) "side" else "bar").apply()
            showStyle()
            OverlayBus.pillStyleChanged?.invoke()
            Toast.makeText(this, getString(if (bar) R.string.pill_style_side_hint else R.string.pill_style_bar_hint), Toast.LENGTH_SHORT).show()
        }

        setupWebView()
        OverlayBus.ocrEvaluator = { payloadJson ->
            webView.evaluateJavascript("window.assessNativeOcr && window.assessNativeOcr($payloadJson);", null)
        }
        OverlayBus.pillAction = { action ->
            webView.evaluateJavascript("window.pillAction && window.pillAction(${org.json.JSONObject.quote(action)});", null)
        }

        if (savedInstanceState == null) handleIntent(intent)
        UpdateChecker.checkOnLaunch(this)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.pokedexWebView)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // Scans are evaluated here while the activity is in the background.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webView.webViewClient = object : WebViewClient() {
            // Android reclaims the WebView renderer under memory pressure (Pokémon GO in front for
            // an hour). Returning false here would crash the whole app, pill and capture included.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                OverlayBus.ocrEvaluator = null              // the pill says "APP WAS CLOSED / TAP OPEN"
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                webViewGone = true
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(WebAppBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == FloatingOverlayService.ACTION_REQUEST_PROJECTION) {
            intent.action = null                                   // never replayed on recreation
            if (FloatingOverlayService.isRunning && Settings.canDrawOverlays(this)) requestScreenCapture()
        }
    }

    // The pill only exists while the app is minimized.
    override fun onResume() {
        super.onResume()
        if (webViewGone) recreate()   // rebuild the page after a renderer kill, now that we are visible
        OverlayBus.pillVisibility?.invoke(false)
        // Pill scans use the page's inspector as scratch state; hand the page back as it was left.
        webView.evaluateJavascript("window.appForegrounded && window.appForegrounded();", null)
    }

    override fun onPause() {
        super.onPause()
        OverlayBus.pillVisibility?.invoke(true)
    }

    // ---------------------------------------------------------------- minimize flow

    /** Overlay permission → capture consent → start service → hide the app behind the pill. */
    private fun minimizeToPill() {
        if (!hasOverlayPermission()) return

        if (FloatingOverlayService.isRunning && FloatingOverlayService.isCapturing) {
            moveTaskToBack(true)
        } else {
            requestScreenCapture()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        Toast.makeText(this, getString(R.string.overlay_permission_desc), Toast.LENGTH_LONG).show()
        startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            REQUEST_OVERLAY_PERMISSION
        )
        return false
    }

    /**
     * Android's own "start capturing?" dialog can't be skipped or pre-approved, so it is kept to
     * one tap per play session: the service keeps the grant alive until the pill is closed.
     */
    private fun requestScreenCapture() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getInt(PREF_CAPTURE_EXPLAINED, 0) < CAPTURE_EXPLAINER_VERSION) {
            // First time only: say what the system dialog is for before it appears.
            AlertDialog.Builder(this)
                .setTitle(R.string.capture_explainer_title)
                .setMessage(R.string.capture_explainer_message)
                .setCancelable(false)
                .setPositiveButton(R.string.capture_explainer_ok) { _, _ ->
                    prefs.edit().putInt(PREF_CAPTURE_EXPLAINED, CAPTURE_EXPLAINER_VERSION).apply()
                    requestScreenCapture()
                }
                .show()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Skips the "single app / entire screen" chooser; the pill needs the whole screen.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> if (Settings.canDrawOverlays(this)) minimizeToPill()
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode != Activity.RESULT_OK || data == null) {
                    Toast.makeText(this, "Screen capture is needed for 1-tap scanning", Toast.LENGTH_LONG).show()
                    return
                }
                // The consent token is single-use and must be consumed by the foreground
                // service (Android 14+), so it is handed over instead of used here.
                val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_START
                    putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(FloatingOverlayService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                moveTaskToBack(true)
            }
        }
    }

    // ---------------------------------------------------------------- JS bridge

    inner class WebAppBridge {
        @JavascriptInterface
        fun minimizeToPill() {
            runOnUiThread { this@MainActivity.minimizeToPill() }
        }

        @JavascriptInterface
        fun autoTrail(): String = OverlayBus.autoTrail

        /** Test builds only: diagnostics sharing is available (see build.gradle.kts). */
        @JavascriptInterface
        fun testSharing(): Boolean = BuildConfig.TEST_SHARING

        /**
         * Test builds only. Hands the diagnostics bundle (My Log without places, AUTO trail, last
         * scan) to the Android share sheet; the tester chooses the destination every time. Only
         * runs when the tester taps the button in My Log.
         */
        @JavascriptInterface
        fun shareDiagnostics(json: String, viaShareSheet: Boolean) {
            if (!BuildConfig.TEST_SHARING) return
            val toast = { msg: String -> runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } }
            try {
                val dir = java.io.File(cacheDir, "diagnostics").apply { mkdirs() }
                val file = java.io.File(dir, "pogo-diagnostics-${System.currentTimeMillis()}.json")
                file.writeText(json)
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PoGo Companion diagnostics ${BuildConfig.VERSION_NAME}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(send, "Share diagnostics")) }
            } catch (e: Exception) {
                toast("Could not share diagnostics: ${e.message}")
            }
        }

        /** Search builder: puts a search string on the clipboard for the player to paste into the game. */
        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Pokémon GO search", text))
            }
        }

        @JavascriptInterface
        fun checkForUpdate() {
            runOnUiThread { UpdateChecker.check(this@MainActivity, manual = true) }
        }

        /**
         * Called by the page after every evaluation so the pill mirrors the in-app HUD.
         * [slotsJson] is an array of [caption, value, cssColour] rows.
         */
        @JavascriptInterface
        fun updatePill(mode: String, target: String, slotsJson: String) {
            val rows = JSONArray(slotsJson)
            val slots = (0 until rows.length()).map { i ->
                val row = rows.getJSONArray(i)
                PillSlot(row.optString(0), row.optString(1), parseCssColor(row.optString(2)))
            }
            runOnUiThread { OverlayBus.pillUpdater?.invoke(PillState(mode, target, slots)) }
        }

        /** Same as updatePill plus the actions the pill should offer (["gone"]). */
        @JavascriptInterface
        fun updatePillEx(mode: String, target: String, slotsJson: String, actionsJson: String) {
            val rows = JSONArray(slotsJson)
            val slots = (0 until rows.length()).map { i ->
                val row = rows.getJSONArray(i)
                PillSlot(row.optString(0), row.optString(1), parseCssColor(row.optString(2)))
            }
            val acts = JSONArray(actionsJson)
            val actions = (0 until acts.length()).map { acts.optString(it) }
            runOnUiThread { OverlayBus.pillUpdater?.invoke(PillState(mode, target, slots, actions)) }
        }
    }

    /** Accepts the two forms the page produces: "#rrggbb" and "rgb(r, g, b)". */
    private fun parseCssColor(css: String): Int? {
        if (css.startsWith("#")) {
            return try { Color.parseColor(css) } catch (_: IllegalArgumentException) { null }
        }
        val rgb = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)""").find(css) ?: return null
        val (r, g, b) = rgb.destructured
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (FloatingOverlayService.isRunning) {
            // Collapse back to the pill instead of exiting
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayBus.ocrEvaluator = null
        OverlayBus.pillAction = null
        webView.destroy()
    }
}
