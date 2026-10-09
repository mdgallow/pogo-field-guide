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
    @Volatile private var dockLeft = false
    /** true = top bar (two short rows across the screen), false = side pill. */
    @Volatile private var barStyle = false
    private var lastState: PillState? = null
    /** Pill bounds in screen pixels, refreshed before each AUTO read; its text is not the game's. */
    @Volatile private var pillRectOnScreen = android.graphics.Rect()

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

    // AUTO (catalogue) mode: log Pokémon as the player swipes through the appraisal view.
    // The player does all the swiping; AUTO only watches, and it is driven by the screen itself:
    // every frame the game draws (throttled to one look per AUTO_SAMPLE_GAP_MS) is fingerprinted
    // on the parts of the card that only change when the Pokémon changes: the appraisal bars, the
    // "caught on ..." banner, the HP line and the weight. The Pokémon model, shadow flames, the
    // team leader and the background are never sampled, so their animation cannot trigger or
    // stall a read. A swipe makes the fingerprint differ from the card last READ; when it then
    // holds still (bars finished filling) the pill blinks once, that clean frame is OCR'd and a
    // short buzz says "logged, swipe on". If it never holds still, it is read anyway after
    // AUTO_FORCE_READ_MS. AUTO stops after AUTO_IDLE_STOP_MS without a change, after
    // AUTO_OFF_PAGE_MS off the storage page, or at AUTO_MAX_MS, and says why on the pill.
    @Volatile private var autoMode = false
    @Volatile private var autoLastSampleAt = 0L
    private var autoUnsettledSince = 0L
    private var autoForced = false
    private var autoForcedAt = 0L
    /**
     * The pill is never hidden or blinked (photosensitivity) and never hidden from captures either
     * (an overlay invisible to screen recording is a malware trait Play Protect flags). Reads
     * simply ignore whatever is under it: OCR lines in its rectangle are dropped, the detectors
     * skip regions it covers (the page then says to move it), and the AUTO fingerprint masks it.
     */
    private var autoNudge = false
    /** An AUTO read is being evaluated; its result gets the "logged" buzz. */
    @Volatile private var autoAwaitingResult = false
    private var autoReads = 0
    private var autoLastActiveAt = 0L
    private var autoOffPageSince = 0L
    private var autoStartedAt = 0L
    /** A manual SCAN interrupted AUTO; AUTO restarts fresh when that scan has rendered. */
    private var autoResumeAfterScan = false
    private val autoTrail = ArrayDeque<String>()   // last ~40 AUTO events, shown in My Log
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoMode) return
            val now = System.currentTimeMillis()
            if (now - autoStartedAt > AUTO_MAX_MS) { stopAuto("10 min: off", "AUTO OFF: 10 MIN LIMIT", "TAP AUTO TO CONTINUE"); return }
            if (now - autoLastActiveAt > AUTO_IDLE_STOP_MS) { stopAuto("idle 30s: off", "AUTO OFF: NOTHING NEW FOR 30 S", "TAP AUTO TO CONTINUE"); return }
            if (autoOffPageSince != 0L && now - autoOffPageSince > AUTO_OFF_PAGE_MS) { stopAuto("left storage page: off", "AUTO OFF: LEFT THE STORAGE PAGE", "TAP AUTO TO CONTINUE"); return }
            if (autoReadPending && now - autoBlinkAt > AUTO_READ_TIMEOUT_MS) {
                // The clean frame never came (a completely still screen sends none): retry.
                autoReadPending = false
                autoReadReady = false
                trail("no frame")
                pillView?.alpha = 1f
                requestFrameSoon()
            }
            mainHandler.postDelayed(this, AUTO_TICK_MS)
        }
    }

    /** One invisible opacity change: makes the mirror send a frame even if the game is still. */
    private val autoNudgeRunnable = Runnable {
        if (autoMode && !autoReadPending) {
            autoNudge = !autoNudge
            pillView?.alpha = if (autoNudge) 0.99f else 1f
        }
    }

    private fun requestFrameSoon() {
        mainHandler.removeCallbacks(autoNudgeRunnable)
        mainHandler.postDelayed(autoNudgeRunnable, AUTO_SAMPLE_GAP_MS + 40)
    }

    private fun stopAuto(trailMsg: String, line1: String, line2: String) {
        trail(trailMsg)
        setAutoMode(false)
        finishScan(PillState.message("AUTO", line1, line2))
    }

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

        const val PREFS = "pogo_overlay"
        /** "side" (default: narrow pill on the left/right edge) or "bar" (strip across the top). */
        const val PREF_PILL_STYLE = "pill_style"
        private const val PREF_PILL_Y = "pill_y"
        private const val PREF_BAR_Y = "bar_y"
        private const val BAR_MARGIN_DP = 6
        private const val BAR_MAX_Y_FRACTION = 0.38f
        /** The pill's own fixed labels: seeing one in a frame means the pill was captured too. */
        private const val PREF_PILL_SIDE = "pill_side"   // "right" (default) or "left"
        /** Default vertical position: where testing settled on, top of the pill ~57% down the screen. */
        private const val DEFAULT_PILL_Y_FRACTION = 0.57f
        private const val PILL_WIDTH_DP = 80
        private const val CAPTION_COLOR = 0xFF94A3B8.toInt()
        private const val DEFAULT_VALUE_COLOR = 0xFFE2E8F0.toInt()
        private const val MAX_CAPTURE_WIDTH = 1080
        private const val SCAN_TIMEOUT_MS = 1500L
        private const val PILL_HIDE_MS = 150L
        private const val EVAL_TIMEOUT_MS = 4000L
        private const val AUTO_TICK_MS = 1000L          // stop-rule check only; sampling follows the frames
        private const val AUTO_SAMPLE_GAP_MS = 200L     // at most one look per this long
        private const val AUTO_FORCE_READ_MS = 2500L    // changed but never still: read anyway
        private const val AUTO_READ_TIMEOUT_MS = 1500L
        private const val AUTO_IDLE_STOP_MS = 30_000L
        private const val AUTO_OFF_PAGE_MS = 5_000L
        private const val AUTO_MAX_MS = 10 * 60_000L
        private const val AUTO_CHANGED_MIN = 5 // fingerprint points that must flip to count as a change (white panels: no noise)
        private const val AUTO_BIG_CHANGE = 60 // a real swipe flips far more than any stray animation
        private const val SIG_SIZE = 3 * 24 + 4 * 30 + 31 * 24 + 8 * 40

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
            if (!visible) setAutoMode(false)
        }
        OverlayBus.pillStyleChanged = { rebuildPill() }
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
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        barStyle = prefs.getString(PREF_PILL_STYLE, "side") == "bar"
        val view = LayoutInflater.from(this).inflate(if (barStyle) R.layout.floating_pill_bar else R.layout.floating_pill, null)
        pillView = view

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // inflate(…, null) drops the root's layout_width, so the pill width is set on the window.
        val density = resources.displayMetrics.density
        val pillWidthPx = if (barStyle) screenWidth - (2 * BAR_MARGIN_DP * density).toInt() else (PILL_WIDTH_DP * density).toInt()
        params = WindowManager.LayoutParams(
            pillWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (barStyle) {
                // A strip across the top, just under the status bar; drag it up or down.
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = prefs.getInt(PREF_BAR_Y, statusBarHeight()).coerceAtMost((screenHeight * BAR_MAX_Y_FRACTION).toInt() - (72 * density).toInt())
            } else {
                // Docked to one edge (right by default, left for left-handed players: drag it across).
                // Vertical position is draggable and remembered.
                dockLeft = prefs.getString(PREF_PILL_SIDE, "right") == "left"
                gravity = Gravity.TOP or (if (dockLeft) Gravity.START else Gravity.END)
                x = 10
                y = prefs.getInt(PREF_PILL_Y, (screenHeight * DEFAULT_PILL_Y_FRACTION).toInt())
            }
        }

        // Dragging works from anywhere on the pill; a touch that doesn't move is a tap.
        attachDragOrTap(view, null)
        attachDragOrTap(view.findViewById(R.id.pillScanBtn)) { requestScan() }
        attachDragOrTap(view.findViewById(R.id.pillAutoBtn)) { setAutoMode(!autoMode) }
        attachDragOrTap(view.findViewById(R.id.pillExpandBtn)) { expandToApp() }
        attachDragOrTap(view.findViewById(R.id.pillCloseBtn)) { stopSelf() }
        attachDragOrTap(view.findViewById(R.id.pillGoneBtn)) { OverlayBus.pillAction?.invoke("gone") }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot show the pill (overlay permission?)", e)
            pillView = null
            stopSelf()
            return
        }
        finishScan(lastState ?: PillState.message("STANDBY", "TAP SCAN ON A POKÉMON"))
    }

    /** Forces a fresh frame (an invisible opacity change), then runs [then] on the main thread. */
    private fun hideForCapture(then: () -> Unit) {
        autoNudge = !autoNudge
        pillView?.alpha = if (autoNudge) 0.99f else 1f
        mainHandler.post(then)
    }

    /** Does the pill cover any of this screen region (fractions of the screen)? */
    private fun pillCovers(fx0: Double, fy0: Double, fx1: Double, fy1: Double): Boolean {
        val r = android.graphics.Rect((screenWidth * fx0).toInt(), (screenHeight * fy0).toInt(), (screenWidth * fx1).toInt(), (screenHeight * fy1).toInt())
        return android.graphics.Rect.intersects(r, pillRectOnScreen)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    /** The player switched between the side pill and the top bar: same state, other layout. */
    private fun rebuildPill() {
        val old = pillView ?: return
        val wasVisible = old.visibility
        setAutoMode(false)
        try { windowManager.removeView(old) } catch (_: Exception) {}
        pillView = null
        createFloatingPill()
        pillView?.visibility = wasVisible
    }

    private fun attachDragOrTap(view: View, onTap: (() -> Unit)?) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0
        var touchStartY = 0f
        var touchStartX = 0f
        var dragging = false

        if (onTap != null) {
            // Accessibility services (TalkBack, switch access) activate via ACTION_CLICK, which only
            // reaches an OnClickListener; the touch listener above it handles drag and forwards taps.
            view.setOnClickListener { vibrateTap(); onTap() }
            view.contentDescription = when (view.id) {
                R.id.pillScanBtn -> "Scan the screen now"
                R.id.pillAutoBtn -> "Auto scan while swiping"
                R.id.pillExpandBtn -> "Open the full app"
                R.id.pillCloseBtn -> "Close the pill"
                R.id.pillGoneBtn -> "I transferred this Pokémon"
                else -> null
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchStartY = event.rawY
                    touchStartX = event.rawX
                    dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchStartY).toInt()
                    val dx = (event.rawX - touchStartX).toInt()
                    if (dragging || abs(dy) > touchSlop || abs(dx) > touchSlop) {
                        dragging = true
                        v.isPressed = false
                        // The top bar must stay above the white card: the name, HP, weight, type row,
                        // appraisal and banner all live below ~38% of the screen and are what AUTO
                        // and the identity match read. The side pill may go anywhere.
                        val limit = if (barStyle) (screenHeight * BAR_MAX_Y_FRACTION).toInt() else screenHeight
                        val maxY = (limit - (pillView?.height ?: 0)).coerceAtLeast(0)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        pillView?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) {
                        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                        prefs.edit().putInt(if (barStyle) PREF_BAR_Y else PREF_PILL_Y, params.y).apply()
                        pillView?.post { rememberPillRect() }
                        // Dragged well past the middle of the screen: dock on the other side.
                        val toLeft = event.rawX < screenWidth * 0.4f
                        val toRight = event.rawX > screenWidth * 0.6f
                        if (!barStyle && ((toLeft && !dockLeft) || (toRight && dockLeft))) {
                            dockLeft = toLeft
                            params.gravity = Gravity.TOP or (if (dockLeft) Gravity.START else Gravity.END)
                            pillView?.let { windowManager.updateViewLayout(it, params) }
                            prefs.edit().putString(PREF_PILL_SIDE, if (dockLeft) "left" else "right").apply()
                            vibrateTap()
                        }
                    } else if (onTap != null) {
                        v.performClick()   // the OnClickListener below runs the action
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
        if (!isCapturing) return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            if (scanRequested.compareAndSet(true, false)) {
                mainHandler.removeCallbacks(scanTimeout)
                val frame = imageToBitmap(image)
                mainHandler.post { showPillReading() }
                runOcr(frame)
            } else if (autoMode) {
                autoSample(image)      // throttles itself
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
            mainHandler.post { showStandby() }
        } finally {
            image?.close()
        }
    }

    // ---------------------------------------------------------------- AUTO mode

    private fun setAutoMode(on: Boolean) {
        if (on && mediaProjection == null) { requestScan(); return }
        autoMode = on
        autoLastSampleAt = 0L
        autoUnsettledSince = 0L
        autoForced = false
        mainHandler.removeCallbacks(autoNudgeRunnable)
        autoReadPending = false
        autoReadReady = false
        autoAwaitingResult = false
        autoLastReadSig = null
        autoLastSeenSig = null
        autoReads = 0
        autoOffPageSince = 0L
        val now = System.currentTimeMillis()
        autoLastActiveAt = now
        autoStartedAt = now
        mainHandler.removeCallbacks(autoTick)
        if (on) {
            trail("on")
            rememberPillRect()
            mainHandler.postDelayed(autoTick, AUTO_TICK_MS)
            requestFrameSoon()             // first look, even if nothing on screen is moving
        }
        mainHandler.post {
            pillView?.let { if (it.alpha < 1f && !scanRequested.get()) it.alpha = 1f }
            updateAutoButton()
        }
        Log.i(TAG, "AUTO mode ${if (on) "on" else "off"}")
    }

    private fun updateAutoButton() {
        pillView?.findViewById<TextView>(R.id.pillAutoBtn)?.apply {
            text = if (!autoMode) "AUTO" else if (barStyle) "AUTO · $autoReads" else "AUTO · $autoReads read"
            setTextColor(if (autoMode) 0xFF34D399.toInt() else 0xFF94A3B8.toInt())
        }
    }

    private fun trail(msg: String) {
        synchronized(autoTrail) {
            autoTrail.addLast("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg")
            while (autoTrail.size > 40) autoTrail.removeFirst()
        }
        OverlayBus.autoTrail = synchronized(autoTrail) { autoTrail.joinToString("\n") }
    }

    /**
     * Runs on the capture thread for every frame while AUTO is on. Cheap by design: at most one
     * sample per AUTO_SAMPLE_MS, each a 24x8 luminance grid over the CP arc and name band.
     */
    /**
     * Brightness at fixed points of the card that are still unless the Pokémon changes, all on
     * flat white panels well away from the 3D model, shadow flames, the team leader (who stands
     * over the right half of the card during an appraisal) and the animated background:
     *  - the HP line under the name,
     *  - the weight number on the left,
     *  - the appraisal panel with its three IV bars (they refill on every swipe),
     *  - the "This X was caught on <date> around <place>" banner.
     * With the appraisal closed the same spots hold the power-up costs and move list, which are
     * just as still. Points under the pill are marked -1 and ignored.
     */
    private fun fingerprint(image: Image): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = image.width
        val h = image.height
        val pill = pillRectOnScreen
        val toScreen = screenWidth.toFloat() / w
        val sig = IntArray(SIG_SIZE)
        var i = 0
        fun sample(fx: Double, fy: Double) {
            val x = (w * fx).toInt().coerceIn(0, w - 1)
            val y = (h * fy).toInt().coerceIn(0, h - 1)
            if (pill.contains((x * toScreen).toInt(), (y * toScreen).toInt())) { sig[i++] = -1; return }
            val o = y * rs + x * ps
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            sig[i++] = (r * 3 + g * 6 + b) / 10
        }
        // HP line ("113 / 113 HP"), centred under the name.
        for (fy in doubleArrayOf(0.468, 0.4725, 0.477)) for (gx in 0 until 24) sample(0.38 + 0.01 * gx, fy)
        // Weight number, left of the type icons.
        for (fy in doubleArrayOf(0.538, 0.545, 0.552, 0.559)) for (gx in 0 until 30) sample(0.085 + 0.007 * gx, fy)
        // Appraisal panel: labels and the three bars (left half of the card only).
        for (gy in 0 until 31) for (gx in 0 until 24) sample(0.10 + 0.0155 * gx, 0.705 + 0.005 * gy)
        // Catch banner: three lines of text.
        for (fy in doubleArrayOf(0.903, 0.908, 0.913, 0.928, 0.933, 0.938, 0.953, 0.958)) for (gx in 0 until 40) sample(0.07 + 0.022 * gx, fy)
        return sig
    }

    private fun rememberPillRect() {
        val v = pillView ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        pillRectOnScreen = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Runs on the capture thread for the frames AUTO asked for. */
    private fun autoSample(image: Image) {
        val now = System.currentTimeMillis()

        if (autoReadReady) {
            // The pill is blinked out: this is the clean frame to read.
            autoReadReady = false
            autoReadPending = false
            val sig = fingerprint(image)
            val frame = imageToBitmap(image)
            if (!looksLikeStorageCard(frame)) {
                if (autoOffPageSince == 0L) autoOffPageSince = now
                trail("not a storage page")
                autoLastReadSig = sig            // do not keep re-taking the same screen (and blinking)
                frame.recycle()
                mainHandler.post { pillView?.alpha = 1f }
                return
            }
            autoOffPageSince = 0L
            autoLastReadSig = sig
            autoLastSeenSig = sig
            autoReads++
            autoAwaitingResult = true
            trail("new card: reading #$autoReads")
            mainHandler.post {
                showPillReading()
                updateAutoButton()
            }
            runOcr(frame, auto = true)
            return
        }

        if (autoReadPending || now - autoLastSampleAt < AUTO_SAMPLE_GAP_MS) return
        autoLastSampleAt = now
        val sig = fingerprint(image)

        // Holding still = same as the look before. Any change is activity (swiping back over
        // cards already read must not count as idle).
        val seen = autoLastSeenSig
        val still = seen != null && changedCount(seen, sig) < AUTO_CHANGED_MIN
        if (!still) autoLastActiveAt = now
        autoLastSeenSig = sig

        val last = autoLastReadSig
        val diff = if (last == null) 999 else changedCount(last, sig)
        if (diff < AUTO_CHANGED_MIN) {              // still (or back on) the card already read
            autoUnsettledSince = 0L
            autoForced = false
            return
        }

        if (!still) {
            // Mid-swipe, or the appraisal bars are still filling. Look again shortly even if the
            // screen then goes quiet; if it never settles, read anyway (once per swipe).
            if (autoUnsettledSince == 0L) { autoUnsettledSince = now; trail("swipe ($diff changed)") }
            val waited = now - autoUnsettledSince
            if (waited < AUTO_FORCE_READ_MS || (autoForced && diff < AUTO_BIG_CHANGE && now - autoForcedAt < 6000)) {
                mainHandler.post { requestFrameSoon() }
                return
            }
            autoForced = true
            autoForcedAt = now
            trail("never settled: reading anyway")
        } else {
            autoForced = false
            trail("settled ($diff changed)")
        }
        autoUnsettledSince = 0L

        // A card that has not been read: blink the pill out and read the next frame.
        autoReadPending = true
        autoBlinkAt = now
        mainHandler.post {
            rememberPillRect()
            hideForCapture { if (autoReadPending) autoReadReady = true }
        }
    }

    private fun changedCount(a: IntArray, b: IntArray, from: Int = 0, to: Int = a.size): Int {
        var n = 0
        for (i in from until to) if (a[i] >= 0 && b[i] >= 0 && abs(a[i] - b[i]) > 30) n++
        return n
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        plane.buffer.rewind()   // the same Image may have been read for the AUTO fingerprint
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
        autoMode = false
        mainHandler.removeCallbacks(autoTick)
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
        if (scanRequested.get()) return
        if (autoMode) {
            // Manual SCAN wins; AUTO restarts from scratch once this scan has rendered.
            autoResumeAfterScan = true
            trail("paused for manual scan")
            setAutoMode(false)
        }

        // Get the pill out of the frame (secure window, or a single short blink), then take it.
        hideForCapture {
            scanRequested.set(true)
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }
    }

    /** Frame is in hand: bring the pill back, showing that it is working. */
    private fun showPillReading() {
        val view = pillView ?: return
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        pillRectOnScreen = android.graphics.Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        view.alpha = 1f
        view.findViewById<TextView>(R.id.pillScanBtn)?.text = "READING…"
    }

    private fun runOcr(bitmap: Bitmap, auto: Boolean = false) {
        val isStorage = looksLikeStorageCard(bitmap)
        // A detector whose region sits under the pill is skipped (never fed pill pixels); the page
        // learns which, so it can ask the player to move the pill if that loses something.
        val blocked = ArrayList<String>()
        val favoriteClear = !pillCovers(0.84, 0.045, 0.97, 0.11).also { if (it) blocked.add("favorite") }
        val shadowClear = !pillCovers(0.22, 0.08, 0.78, 0.34).also { if (it) blocked.add("shadow") }
        val dynamaxClear = !pillCovers(0.32, 0.605, 0.41, 0.655).also { if (it) blocked.add("dynamax") }
        val appraisalClear = !pillCovers(0.07, 0.68, 0.52, 0.88).also { if (it) blocked.add("appraisal") }
        val isFavorite = isStorage && favoriteClear && looksFavorited(bitmap)
        val isShadow = isStorage && shadowClear && looksShadow(bitmap)
        val isDynamax = isStorage && dynamaxClear && looksDynamax(bitmap)
        val ivs = if (isStorage && appraisalClear) readIvBars(bitmap) else null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> evaluate(text, bitmap.width, bitmap.height, isStorage, isFavorite, ivs, auto, isShadow, isDynamax, blocked) }
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
        // Points under the pill capture as black (secure window) or as the pill itself: skip them.
        val pill = pillRectOnScreen
        val toScreen = screenWidth.toFloat() / bitmap.width
        var white = 0; var seen = 0
        for (ry in floatArrayOf(0.62f, 0.68f, 0.72f)) {
            for (rx in floatArrayOf(0.12f, 0.20f, 0.30f)) {
                val x = (bitmap.width * rx).toInt(); val y = (bitmap.height * ry).toInt()
                if (pill.contains((x * toScreen).toInt(), (y * toScreen).toInt())) continue
                seen++
                val c = bitmap.getPixel(x, y)
                if ((c shr 16 and 0xFF) > 205 && (c shr 8 and 0xFF) > 205 && (c and 0xFF) > 205) white++
            }
        }
        return seen == 0 || white >= minOf(3, seen)
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

    /**
     * Shadow Pokémon are drawn with dark-violet flames hugging the body. Same rule as
     * looksShadow() in index.html: the ring around the body is mostly dark violet and much
     * more so than the screen edges (a purple night sky is uniform; Mewtwo's backdrop is bright).
     */
    private fun looksShadow(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        fun darkViolet(c: Int): Boolean {
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            if (max < 0.18f || max > 0.62f || d / max < 0.35f) return false
            var hue = when (max) {
                r -> ((g - b) / d + 6f) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            hue /= 6f
            return hue in 0.68f..0.84f
        }
        var ringN = 0; var ringV = 0; var edgeN = 0; var edgeV = 0
        var y = (h * 0.10).toInt()
        while (y < (h * 0.34).toInt()) {
            var x = (w * 0.22).toInt()
            while (x < (w * 0.78).toInt()) {
                if (!(x > w * 0.36 && x < w * 0.64 && y > h * 0.14 && y < h * 0.31)) {
                    ringN++; if (darkViolet(bmp.getPixel(x, y))) ringV++
                }
                x += 3
            }
            y += 3
        }
        y = (h * 0.08).toInt()
        while (y < (h * 0.34).toInt()) {
            var x = (w * 0.02).toInt()
            while (x < (w * 0.98).toInt()) {
                if (x < w * 0.12 || x > w * 0.88) {
                    edgeN++; if (darkViolet(bmp.getPixel(x, y))) edgeV++
                }
                x += 3
            }
            y += 3
        }
        val ring = ringV.toFloat() / maxOf(1, ringN)
        val edge = edgeV.toFloat() / maxOf(1, edgeN)
        return ring >= 0.45f && ring - edge >= 0.08f
    }

    /** Magenta round Dynamax badge under the height/weight row; same rule as looksDynamax() in index.html. */
    private fun looksDynamax(bmp: Bitmap): Boolean {
        val x0 = (bmp.width * 0.32).toInt()
        val x1 = (bmp.width * 0.41).toInt()
        val y0 = (bmp.height * 0.605).toInt()
        val y1 = (bmp.height * 0.655).toInt()
        var n = 0
        var m = 0
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8 and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val max = maxOf(r, g, b)
                val d = max - minOf(r, g, b)
                n++
                if (max < 0.45f || d / max < 0.45f) continue
                var hue = when (max) {
                    r -> ((g - b) / d + 6f) % 6f
                    g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                }
                hue /= 6f
                if (hue >= 0.88f || hue <= 0.02f) m++
            }
        }
        return n > 0 && m.toFloat() / n >= 0.30f
    }

    private fun evaluate(text: Text, width: Int, height: Int, isStorage: Boolean, isFavorite: Boolean, ivs: IntArray?, auto: Boolean = false, isShadow: Boolean = false, isDynamax: Boolean = false, blocked: List<String> = emptyList()) {
        val lines = JSONArray()
        val plainText = StringBuilder()
        // AUTO reads keep the pill on screen: skip any text inside it (scaled to capture pixels).
        val scale = width.toFloat() / screenWidth
        val pr = pillRectOnScreen
        val pillRect = android.graphics.Rect((pr.left * scale).toInt(), (pr.top * scale).toInt(), (pr.right * scale).toInt(), (pr.bottom * scale).toInt())
        // The pill is in the frame (or black, in secure mode): any text inside its rectangle is its
        // own, never the game's.
        val maskPill = true
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (maskPill && android.graphics.Rect.intersects(box, pillRect)) continue
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
                    .put("ivs", ivs?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).put("auto", auto)
                    .put("shadow", isShadow).put("dynamax", isDynamax).put("blocked", JSONArray(blocked)).toString()
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
        lastState = state
        if (autoAwaitingResult) {
            autoAwaitingResult = false
            // A real answer (not a "nothing to read"): logged, the player can swipe to the next one.
            if (autoMode && !state.mode.equals("STANDBY", ignoreCase = true)) vibrateTap()
        }
        val v = pillView ?: return
        v.alpha = 1f
        val mode = state.mode.uppercase()

        v.findViewById<TextView>(R.id.pillScanBtn)?.text = "SCAN"
        if (autoResumeAfterScan) {
            autoResumeAfterScan = false
            mainHandler.postDelayed({ if (!autoMode && mediaProjection != null) setAutoMode(true) }, 400)
        }
        updateAutoButton()
        v.findViewById<TextView>(R.id.pillModeBadge)?.text = mode
        val targetView = v.findViewById<TextView>(R.id.pillTargetLabel)
        if (barStyle) {
            // One line; kept in the layout when empty so the buttons on the right do not jump.
            targetView.text = state.target.replace("\n", " · ")
            targetView.visibility = if (state.target.isBlank()) View.INVISIBLE else View.VISIBLE
        } else {
            setTextOrHide(targetView, state.target, 0xFFFFFFFF.toInt())
        }

        val inflater = LayoutInflater.from(this)
        val container = v.findViewById<LinearLayout>(R.id.pillSlots)
        container.removeAllViews()
        val shown = state.slots.filter { it.value.isNotBlank() }
        // Top bar: movesets go on a row of their own; the answer row holds five at most, and the
        // log count is the one to give up.
        val movesRow = v.findViewById<LinearLayout>(R.id.pillSlotsMoves)
        movesRow?.removeAllViews()
        val isMoves = { slot: PillSlot -> barStyle && slot.caption.contains("MOVES") }
        val answers = shown.filterNot(isMoves)
        val answerRow = if (barStyle && answers.size > 4) answers.filter { it.caption != "LOGGED" } else answers
        for (slot in answerRow + shown.filter(isMoves)) {
            val target = if (isMoves(slot) && movesRow != null) movesRow else container
            val row = inflater.inflate(R.layout.pill_slot, target, false)
            setTextOrHide(row.findViewById(R.id.pillSlotCaption), slot.caption, CAPTION_COLOR)
            val valueView = row.findViewById<TextView>(R.id.pillSlotValue)
            setTextOrHide(valueView, slot.value, slot.color ?: DEFAULT_VALUE_COLOR)
            if (barStyle) {
                // Side by side: the sentence-like answers get more of the width than the short ones.
                val weight = when {
                    isMoves(slot) -> 1f
                    slot.caption.isBlank() || slot.caption == "REASON" -> 2.4f
                    slot.caption == "VERDICT" || slot.caption == "BALL" -> 1f
                    else -> 1.4f
                }
                row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                row.background = null
                row.setPadding((2 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt(), 0)
                valueView.maxLines = if (isMoves(slot)) 3 else 2
                valueView.ellipsize = android.text.TextUtils.TruncateAt.END
            }
            target.addView(row)
        }
        movesRow?.visibility = if (movesRow != null && movesRow.childCount > 0) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.pillGoneBtn)?.visibility = if (state.actions.contains("gone")) View.VISIBLE else View.GONE
        // The pill grows and shrinks with what it says; AUTO's fingerprint skips whatever is under it.
        v.post { rememberPillRect() }
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
        OverlayBus.pillStyleChanged = null
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
