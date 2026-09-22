package com.pogo.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Foreground service that owns everything needed while the app is minimized:
 * the floating pill, the MediaProjection screen mirror, and on-device OCR.
 * A scan never brings MainActivity forward, so the captured frame is always
 * the game underneath the pill.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var pillView: View? = null

    private var screenWidth = 1080
    private var screenHeight = 2340
    private var screenDensity = 400
    private var captureWidth = 1080
    private var captureHeight = 2340

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val scanRequested = AtomicBoolean(false)
    private val scanTimeout = Runnable {
        if (scanRequested.compareAndSet(true, false)) {
            Log.w(TAG, "No frame arrived for scan")
            finishScan(PillState.message("STANDBY", "NO PICTURE", "TAP SCAN AGAIN"))
        }
    }

    /** Guards against the backgrounded WebView never answering an evaluation request. */
    private val evalTimeout = Runnable {
        Log.w(TAG, "WebView did not answer the evaluation request")
        showStandby()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System or user revoked capture (e.g. screen lock on Android 14+).
            releaseCapture()
            finishScan(PillState.message("PAUSED", "SCREEN ACCESS ENDED", "TAP SCAN TO RESTART"))
        }
    }

    companion object {
        private const val TAG = "PogoOverlay"
        const val CHANNEL_ID = "pogo_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.pogo.companion.START_OVERLAY"
        const val ACTION_REQUEST_PROJECTION = "com.pogo.companion.REQUEST_PROJECTION"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val PREFS = "pogo_overlay"
        private const val PREF_PILL_Y = "pill_y"
        private const val PILL_WIDTH_DP = 80
        private const val CAPTION_COLOR = 0xFF94A3B8.toInt()
        private const val DEFAULT_VALUE_COLOR = 0xFFE2E8F0.toInt()
        private const val MAX_CAPTURE_WIDTH = 1080
        private const val SCAN_TIMEOUT_MS = 1500L
        private const val PILL_HIDE_MS = 150L
        private const val EVAL_TIMEOUT_MS = 4000L

        @Volatile var isRunning = false
            private set
        @Volatile var isCapturing = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        readScreenMetrics()
        createNotificationChannel()
        OverlayBus.pillUpdater = { state -> finishScan(state) }
        OverlayBus.pillVisibility = { visible ->
            pillView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

            // Android 14+: the mediaProjection foreground type is only legal after the user
            // granted capture consent, and must be active before getMediaProjection().
            goForeground()
            if (pillView == null) createFloatingPill()
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                startCapture(resultCode, resultData)
            }
        }
        // Not sticky: a system restart would arrive without a consent token.
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PoGo Floating Pill HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating companion active over Pokémon GO"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PoGo Companion pill active")
            .setContentText("Tap ⚡ SCAN on the pill, or tap here to open the full Pokédex")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- pill window

    private fun readScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun createFloatingPill() {
        val view = LayoutInflater.from(this).inflate(R.layout.floating_pill, null)
        pillView = view

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // inflate(…, null) drops the root's layout_width, so the pill width is set on the window.
        val pillWidthPx = (PILL_WIDTH_DP * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            pillWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Docked to the right edge; only the vertical position is draggable.
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_PILL_Y, 350)
        }

        // Dragging works from anywhere on the pill; a touch that doesn't move is a tap.
        attachDragOrTap(view, null)
        attachDragOrTap(view.findViewById(R.id.pillScanBtn)) { requestScan() }
        attachDragOrTap(view.findViewById(R.id.pillExpandBtn)) { expandToApp() }
        attachDragOrTap(view.findViewById(R.id.pillCloseBtn)) { stopSelf() }

        windowManager.addView(view, params)
        finishScan(PillState.message("STANDBY", "TAP SCAN ON A POKÉMON"))
    }

    private fun attachDragOrTap(view: View, onTap: (() -> Unit)?) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0
        var touchStartY = 0f
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchStartY = event.rawY
                    dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchStartY).toInt()
                    if (dragging || abs(dy) > touchSlop) {
                        dragging = true
                        v.isPressed = false
                        val maxY = (screenHeight - (pillView?.height ?: 0)).coerceAtLeast(0)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        pillView?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_PILL_Y, params.y).apply()
                    } else if (onTap != null) {
                        v.performClick()
                        vibrateTap()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
    }

    private fun expandToApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

    // ---------------------------------------------------------------- screen capture

    private fun startCapture(resultCode: Int, resultData: Intent) {
        releaseCapture()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData) ?: return
            // Android 14+ requires a registered callback before createVirtualDisplay().
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            // OCR doesn't need more than 1080px of width; smaller frames cost less to mirror.
            val scale = minOf(1f, MAX_CAPTURE_WIDTH.toFloat() / screenWidth)
            captureWidth = (screenWidth * scale).toInt()
            captureHeight = (screenHeight * scale).toInt()

            val thread = HandlerThread("pogo-capture").also { it.start() }
            captureThread = thread
            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ onFrameAvailable(it) }, Handler(thread.looper))
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "PoGoScreenCapture",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            isCapturing = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            releaseCapture()
        }
    }

    /**
     * Runs on the capture thread for every mirrored frame. Frames are always drained so the
     * reader's queue never fills with stale images; one is only decoded when a scan is pending.
     */
    private fun onFrameAvailable(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            if (scanRequested.compareAndSet(true, false)) {
                mainHandler.removeCallbacks(scanTimeout)
                val frame = imageToBitmap(image)
                mainHandler.post { showPillReading() }
                runOcr(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            mainHandler.post { showStandby() }
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun releaseCapture() {
        isCapturing = false
        scanRequested.set(false)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(evalTimeout)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        mediaProjection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        mediaProjection = null
    }

    // ---------------------------------------------------------------- scan + evaluation

    private fun requestScan() {
        if (mediaProjection == null) {
            // Capture consent was never granted or was revoked: the activity has to ask again.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = ACTION_REQUEST_PROJECTION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            return
        }
        val view = pillView ?: return
        if (scanRequested.get() || view.alpha < 1f) return

        // Blink the pill out so nothing underneath it (date tag, favorite star, candy label)
        // is hidden from the scan. The mirror needs a moment to show the pill gone; the second
        // alpha change then forces a fresh frame even if the game screen is completely still.
        view.alpha = 0f
        mainHandler.postDelayed({
            scanRequested.set(true)
            pillView?.alpha = 0.01f
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }, PILL_HIDE_MS)
    }

    /** Frame is in hand: bring the pill back, showing that it is working. */
    private fun showPillReading() {
        val view = pillView ?: return
        view.alpha = 1f
        view.findViewById<TextView>(R.id.pillScanBtn)?.text = "READING…"
    }

    private fun runOcr(bitmap: Bitmap) {
        val isStorage = looksLikeStorageCard(bitmap)
        val isFavorite = isStorage && looksFavorited(bitmap)
        val ivs = if (isStorage) readIvBars(bitmap) else null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> evaluate(text, bitmap.width, bitmap.height, isStorage, isFavorite, ivs) }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                showStandby()
            }
            .addOnCompleteListener { bitmap.recycle() }
    }

    /**
     * Pokémon detail pages draw a white info card over the lower half; encounters don't.
     * Sampled on the left so the pill (docked right) can never produce a false white read.
     */
    private fun looksLikeStorageCard(bitmap: Bitmap): Boolean {
        var white = 0
        for (ry in floatArrayOf(0.62f, 0.68f, 0.72f)) {
            for (rx in floatArrayOf(0.12f, 0.20f, 0.30f)) {
                val c = bitmap.getPixel((bitmap.width * rx).toInt(), (bitmap.height * ry).toInt())
                if ((c shr 16 and 0xFF) > 205 && (c shr 8 and 0xFF) > 205 && (c and 0xFF) > 205) white++
            }
        }
        return white >= 3
    }

    /**
     * The in-game favorite star (top right of the storage page) is solid gold when set and a grey
     * outline when not. Same check as looksFavorited() in index.html.
     */
    private fun looksFavorited(bitmap: Bitmap): Boolean {
        val x0 = (bitmap.width * 0.84f).toInt()
        val y0 = (bitmap.height * 0.045f).toInt()
        val x1 = (bitmap.width * 0.97f).toInt()
        val y1 = (bitmap.height * 0.11f).toInt()
        var gold = 0
        var total = 0
        for (y in y0 until y1 step 4) {
            for (x in x0 until x1 step 4) {
                val c = bitmap.getPixel(x, y)
                total++
                if ((c shr 16 and 0xFF) > 220 && (c shr 8 and 0xFF) > 165 && (c and 0xFF) < 100) gold++
            }
        }
        return total > 0 && gold.toFloat() / total > 0.10f
    }

    /**
     * Reads the appraisal panel's three IV bars (Attack / Defense / HP, 15 units each in three
     * blocks of five). Same algorithm and colours as readIvBars() in index.html, calibrated on
     * 36 real screenshots. Returns null when no appraisal panel is on screen.
     */
    private fun readIvBars(bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        fun kind(c: Int): Int { // 0 none, 1 rail, 2 red (full), 3 orange (partial)
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (r in 206..239 && g in 206..239 && b in 201..239 && maxOf(r, g, b) - minOf(r, g, b) < 12) return 1
            if (r > 195 && g in 106..149 && b in 106..154 && r - g > 70) return 2
            if (r > 220 && g in 141..189 && b < 120) return 3
            return 0
        }
        fun white(c: Int) = (c shr 16 and 0xFF) > 240 && (c shr 8 and 0xFF) > 240 && (c and 0xFF) > 240
        val x0 = (w * 0.08).toInt()
        val x1 = (w * 0.55).toInt()

        val bands = ArrayList<IntArray>() // [start, end]
        for (y in (h * 0.55).toInt() until (h * 0.95).toInt()) {
            var n = 0
            var x = x0
            while (x < x1) {
                if (kind(bmp.getPixel(x, y)) != 0) n++
                x += 2
            }
            if (n * 2 > (x1 - x0) * 0.28) {
                if (bands.isNotEmpty() && y - bands.last()[1] <= 2) bands.last()[1] = y else bands.add(intArrayOf(y, y))
            }
        }

        val bars = ArrayList<Int>()
        for (b in bands) {
            val bh = b[1] - b[0] + 1
            if (bh < h * 0.004 || bh > h * 0.016) continue
            val y = (b[0] + b[1]) / 2
            val xs = (x0 until x1).filter { kind(bmp.getPixel(it, y)) != 0 }
            if (xs.isEmpty()) continue
            val runs = ArrayList<IntArray>()
            for (x in xs) {
                if (runs.isNotEmpty() && x - runs.last()[1] <= w * 0.03) runs.last()[1] = x else runs.add(intArrayOf(x, x))
            }
            val best = runs.maxByOrNull { it[1] - it[0] } ?: continue
            val left = best[0]
            val right = best[1]
            if (right - left < w * 0.25) continue
            if (left < 12 || right + 12 >= w) continue
            if (!white(bmp.getPixel(left - 12, y)) || !white(bmp.getPixel(right + 12, y))) continue
            var filled = 0
            var total = 0
            for (x in left..right) {
                val k = kind(bmp.getPixel(x, y))
                if (k != 0) {
                    total++
                    if (k != 1) filled++
                }
            }
            bars.add(Math.round(filled.toFloat() / total * 15))
        }
        return if (bars.size == 3) bars.toIntArray() else null
    }

    private fun evaluate(text: Text, width: Int, height: Int, isStorage: Boolean, isFavorite: Boolean, ivs: IntArray?) {
        val lines = JSONArray()
        val plainText = StringBuilder()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                plainText.append(line.text).append('\n')
                lines.put(
                    JSONObject()
                        .put("t", line.text)
                        .put("x", box.left.toDouble() / width)
                        .put("y", box.top.toDouble() / height)
                        .put("w", box.width().toDouble() / width)
                        .put("h", box.height().toDouble() / height)
                )
            }
        }

        val evaluator = OverlayBus.ocrEvaluator
        if (evaluator != null) {
            // The WebView holds the Pokédex data; it answers through OverlayBus.pillUpdater.
            mainHandler.postDelayed(evalTimeout, EVAL_TIMEOUT_MS)
            evaluator(
                JSONObject().put("storage", isStorage).put("favorite", isFavorite).put("lines", lines)
                    .put("ivs", ivs?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).toString()
            )
            return
        }

        // Full app was closed, so no species data: report the CP we can read and say why.
        val cp = Regex("""\b[CG][PR]\s?(\d{2,5})""").find(plainText)?.groupValues?.get(1)
        if (cp == null) {
            showStandby()
        } else {
            finishScan(
                PillState(
                    if (isStorage) "STORAGE" else "CATCH", "CP $cp",
                    listOf(PillSlot("", "APP WAS CLOSED"), PillSlot("", "TAP OPEN, THEN MINIMIZE AGAIN"))
                )
            )
        }
    }

    private fun showStandby() {
        finishScan(PillState.message("STANDBY", "NOTHING TO READ", "TAP SCAN ON A POKÉMON"))
    }

    /** Ends any pending scan and renders [state]. Always runs on the main thread. */
    private fun finishScan(state: PillState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishScan(state) }
            return
        }
        mainHandler.removeCallbacks(evalTimeout)
        val v = pillView ?: return
        v.alpha = 1f
        val mode = state.mode.uppercase()

        v.findViewById<TextView>(R.id.pillScanBtn)?.text = "SCAN"
        v.findViewById<TextView>(R.id.pillModeBadge)?.text = mode
        setTextOrHide(v.findViewById(R.id.pillTargetLabel), state.target, 0xFFFFFFFF.toInt())

        val inflater = LayoutInflater.from(this)
        val container = v.findViewById<LinearLayout>(R.id.pillSlots)
        container.removeAllViews()
        for (slot in state.slots) {
            if (slot.value.isBlank()) continue
            val row = inflater.inflate(R.layout.pill_slot, container, false)
            setTextOrHide(row.findViewById(R.id.pillSlotCaption), slot.caption, CAPTION_COLOR)
            setTextOrHide(row.findViewById(R.id.pillSlotValue), slot.value, slot.color ?: DEFAULT_VALUE_COLOR)
            container.addView(row)
        }
    }

    /** Empty lines collapse so the pill is never taller than what it has to say. */
    private fun setTextOrHide(view: TextView, value: String, color: Int) {
        view.text = value
        view.setTextColor(color)
        view.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun vibrateTap() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        OverlayBus.pillUpdater = null
        OverlayBus.pillVisibility = null
        releaseCapture()
        recognizer.close()
        pillView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        pillView = null
    }
}
