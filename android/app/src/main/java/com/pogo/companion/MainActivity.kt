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

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_MEDIA_PROJECTION = 102
        private const val PREFS = "pogo_overlay"
        private const val PREF_CAPTURE_EXPLAINED = "capture_explained"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.appTitle).text = "${getString(R.string.app_name)} v$versionName"
        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener { minimizeToPill() }

        setupWebView()
        OverlayBus.ocrEvaluator = { payloadJson ->
            webView.evaluateJavascript("window.assessNativeOcr && window.assessNativeOcr($payloadJson);", null)
        }

        handleIntent(intent)
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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(WebAppBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == FloatingOverlayService.ACTION_REQUEST_PROJECTION) {
            requestScreenCapture()
        }
    }

    // The pill only exists while the app is minimized.
    override fun onResume() {
        super.onResume()
        OverlayBus.pillVisibility?.invoke(false)
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
        if (!prefs.getBoolean(PREF_CAPTURE_EXPLAINED, false)) {
            // First time only: say what the system dialog is for before it appears.
            AlertDialog.Builder(this)
                .setTitle(R.string.capture_explainer_title)
                .setMessage(R.string.capture_explainer_message)
                .setCancelable(false)
                .setPositiveButton(R.string.capture_explainer_ok) { _, _ ->
                    prefs.edit().putBoolean(PREF_CAPTURE_EXPLAINED, true).apply()
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
        webView.destroy()
    }
}
