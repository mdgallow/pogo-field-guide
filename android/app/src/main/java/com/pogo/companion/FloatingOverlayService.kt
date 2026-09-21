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
import android.graphics.Rect
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
    /** Pill bounds in capture-frame pixels; OCR text inside it is the pill's own labels. */
    @Volatile private var pillRectInCapture = Rect()

    private val scanTimeout = Runnable {
        if (scanRequested.compareAndSet(true, false)) {
            Log.w(TAG, "No frame arrived for scan")
            finishScan(PillState("STANDBY", "", "", "NO PICTURE", "", "TAP SCAN AGAIN", "", ""))
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
            finishScan(PillState("PAUSED", "", "", "SCREEN ACCESS ENDED", "", "TAP SCAN TO RESTART", "", ""))
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
        private const val PILL_WIDTH_DP = 72
        private const val CAPTION_COLOR = 0xFF94A3B8.toInt()
        private const val MAX_CAPTURE_WIDTH = 1080
        private const val SCAN_TIMEOUT_MS = 1500L
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
                runOcr(imageToBitmap(image))
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
        if (scanRequested.get()) return

        val view = pillView ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val scale = captureWidth.toFloat() / screenWidth
        pillRectInCapture = Rect(
            (loc[0] * scale).toInt(), (loc[1] * scale).toInt(),
            ((loc[0] + view.width) * scale).toInt(), ((loc[1] + view.height) * scale).toInt()
        )

        // Changing the label also forces a fresh frame if the screen underneath is static.
        view.findViewById<TextView>(R.id.pillScanBtn)?.text = "READING…"
        scanRequested.set(true)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun runOcr(bitmap: Bitmap) {
        val isStorage = looksLikeStorageCard(bitmap)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> evaluate(text, bitmap.width, bitmap.height, isStorage) }
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

    private fun evaluate(text: Text, width: Int, height: Int, isStorage: Boolean) {
        val pillRect = pillRectInCapture
        val lines = JSONArray()
        val plainText = StringBuilder()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (Rect.intersects(box, pillRect)) continue
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
            evaluator(JSONObject().put("storage", isStorage).put("lines", lines).toString())
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
                    "", "APP WAS CLOSED", "", "TAP OPEN, THEN MINIMIZE AGAIN", "", ""
                )
            )
        }
    }

    private fun showStandby() {
        finishScan(PillState("STANDBY", "", "", "NOTHING TO READ", "", "TAP SCAN ON A POKÉMON", "", ""))
    }

    /** Ends any pending scan and renders [state]. Always runs on the main thread. */
    private fun finishScan(state: PillState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishScan(state) }
            return
        }
        mainHandler.removeCallbacks(evalTimeout)
        val v = pillView ?: return
        val mode = state.mode.uppercase()
        val isStorage = mode == "STORAGE"

        v.findViewById<TextView>(R.id.pillScanBtn)?.text = "SCAN"
        v.findViewById<TextView>(R.id.pillModeBadge)?.text = mode
        setTextOrHide(v.findViewById(R.id.pillTargetLabel), state.target, 0xFFFFFFFF.toInt())

        setTextOrHide(v.findViewById(R.id.pillSlot1Caption), state.caption1, CAPTION_COLOR)
        setTextOrHide(v.findViewById(R.id.pillSlot1Value), state.value1, if (isStorage) 0xFFF87171.toInt() else 0xFFFBBF24.toInt())
        setTextOrHide(v.findViewById(R.id.pillSlot2Caption), state.caption2, CAPTION_COLOR)
        setTextOrHide(v.findViewById(R.id.pillSlot2Value), state.value2, 0xFF34D399.toInt())
        setTextOrHide(v.findViewById(R.id.pillSlot3Caption), state.caption3, CAPTION_COLOR)
        setTextOrHide(v.findViewById(R.id.pillSlot3Value), state.value3, 0xFFC084FC.toInt())
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
