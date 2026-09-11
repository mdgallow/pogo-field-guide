package com.pogo.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnLaunchOverlay: Button

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2340
    private var screenDensity = 400

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_MEDIA_PROJECTION = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupViews()
        checkOverlayPermission()
    }

    private fun setupViews() {
        btnLaunchOverlay = findViewById(R.id.btnLaunchOverlay)
        webView = findViewById(R.id.pokedexWebView)

        btnLaunchOverlay.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayAndCollapse()
            }
        }

        setupWebView()
    }

    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(WebAppBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable 'Display over other apps' to float the pill", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                return false
            }
        }
        return true
    }

    private fun startOverlayAndCollapse() {
        // Start foreground floating pill service
        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Request Screen Capture permission if not granted yet
        if (mediaProjection == null) {
            mediaProjectionManager?.let {
                startActivityForResult(it.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
        } else {
            // Move app to background so Pokémon GO is directly visible!
            moveTaskToBack(true)
            Toast.makeText(this, "📱 Floating Pill Active on screen edge!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == FloatingOverlayService.ACTION_TRIGGER_SCAN) {
            performScreenScan()
        }
    }

    private fun performScreenScan() {
        if (mediaProjection == null) {
            mediaProjectionManager?.let {
                startActivityForResult(it.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
            return
        }

        setupImageReader()
        // Wait 150ms for frame buffer
        handler.postDelayed({
            captureAndEvaluateFrame()
        }, 150)
    }

    private fun setupImageReader() {
        if (imageReader != null) return

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PoGoScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureAndEvaluateFrame() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Clean cropped bitmap to true screen size
                val cleanBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                runOcrOnBitmap(cleanBitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image?.close()
            // Keep app in background so Pokémon GO stays visible
            moveTaskToBack(true)
        }
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                evaluateDetectedText(fullText, bitmap)
            }
            .addOnFailureListener {
                updateOverlayHUD("STANDBY", "⚪", "IDLE", "⚪", "STANDBY", "⚪", "READY")
            }
    }

    private fun evaluateDetectedText(ocrText: String, bitmap: Bitmap) {
        // 1. Check for CP in text
        val cpMatch = Regex("""(?:CP|CO|CR|GR|OP)?\s*(\d{2,5})""", RegexOption.IGNORE_CASE).find(ocrText)
        val cpVal = cpMatch?.groupValues?.get(1)?.toIntOrNull()

        // 2. Check for Left-Side Card Luminance to dynamically distinguish Storage vs Catch
        val isStorageCard = checkLeftCardLuminance(bitmap)

        if (isStorageCard) {
            // BRANCH 2: Storage Box
            val isDel = (cpVal ?: 0) < 1500
            val act = if (isDel) "DELETE" else "KEEP"
            val rating = "0-2★ JUNK"
            val candy = "+1 CANDY"
            updateOverlayHUD("STORAGE", "🗑️", act, "⭐", rating, "🍬", candy)
        } else if (cpVal != null && cpVal > 10) {
            // BRANCH 1: Catch Window
            val berry = if (cpVal > 800) "RAZZ" else "PINAP"
            val icon = if (cpVal > 800) "🍓" else "🍍"
            val ceiling = "L50 $cpVal"
            val action = if (cpVal > 1000) "KEEP" else "XFER"
            updateOverlayHUD("CATCH", icon, berry, "🎯", ceiling, "🗑️", action)
        } else {
            // BRANCH 3: Standby
            updateOverlayHUD("STANDBY", "⚪", "IDLE", "⚪", "STANDBY", "⚪", "READY")
        }
    }

    private fun checkLeftCardLuminance(bitmap: Bitmap): Boolean {
        try {
            val testPoints = arrayOf(
                Pair(0.15f, 0.65f), Pair(0.25f, 0.65f),
                Pair(0.15f, 0.72f), Pair(0.25f, 0.72f)
            )
            var whiteCount = 0
            for ((rx, ry) in testPoints) {
                val px = (bitmap.width * rx).toInt()
                val py = (bitmap.height * ry).toInt()
                val color = bitmap.getPixel(px, py)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r > 205 && g > 205 && b > 205) whiteCount++
            }
            return whiteCount >= 2
        } catch (_: Exception) {
            return false
        }
    }

    private fun updateOverlayHUD(
        mode: String,
        berryIcon: String, berryLabel: String,
        catchIcon: String, catchLabel: String,
        actionIcon: String, actionLabel: String
    ) {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_UPDATE_HUD
            putExtra("mode", mode)
            putExtra("berryIcon", berryIcon)
            putExtra("berryLabel", berryLabel)
            putExtra("catchIcon", catchIcon)
            putExtra("catchLabel", catchLabel)
            putExtra("actionIcon", actionIcon)
            putExtra("actionLabel", actionLabel)
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startOverlayAndCollapse()
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                setupImageReader()
                moveTaskToBack(true)
                Toast.makeText(this, "✅ 1-Tap Screen Capture Ready!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun collapseToOverlay() {
            runOnUiThread {
                moveTaskToBack(true)
            }
        }

        @JavascriptInterface
        fun startOverlayService() {
            runOnUiThread {
                startOverlayAndCollapse()
            }
        }
    }

    override fun onBackPressed() {
        if (FloatingOverlayService.isRunning) {
            // Collapse to Pokémon GO instead of exiting app!
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        recognizer.close()
    }
}
