package com.pogo.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var pillView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var isDragging = false

    companion object {
        const val CHANNEL_ID = "pogo_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_HUD = "com.pogo.companion.UPDATE_HUD"
        const val ACTION_TRIGGER_SCAN = "com.pogo.companion.TRIGGER_SCAN"
        const val ACTION_STOP_OVERLAY = "com.pogo.companion.STOP_OVERLAY"

        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingPill()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PoGo Floating Pill HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating companion active over Pokémon GO"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
            .setContentTitle("PoGo Companion v2.0.0 Active")
            .setContentText("Tap to open full Pokédex or tap pill to scan")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createFloatingPill() {
        val inflater = LayoutInflater.from(this)
        pillView = inflater.inflate(R.layout.floating_pill, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = 350
        }

        setupTouchAndDrag()
        setupButtons()

        windowManager.addView(pillView, params)
    }

    private fun setupTouchAndDrag() {
        var initialY = 0
        var initialTouchY = 0f

        pillView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    isDragging = false
                    false // allow child clicks
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dy) > 12) {
                        isDragging = true
                        params.y = initialY + dy
                        windowManager.updateViewLayout(pillView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        val v = pillView ?: return

        // 1-Tap ⚡ SCAN Button
        v.findViewById<View>(R.id.pillScanBtn)?.setOnClickListener {
            if (!isDragging) {
                vibrateTap()
                val scanText = v.findViewById<TextView>(R.id.pillScanText)
                scanText?.text = "..."
                
                // Request MainActivity to perform MediaProjection capture
                val scanIntent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_TRIGGER_SCAN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(scanIntent)
            }
        }

        // ⛶ EXPAND Button (Opens Full Pokédex Dashboard)
        v.findViewById<View>(R.id.pillExpandBtn)?.setOnClickListener {
            if (!isDragging) {
                vibrateTap()
                val expandIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(expandIntent)
            }
        }

        // ✕ CLOSE Button
        v.findViewById<View>(R.id.pillCloseBtn)?.setOnClickListener {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_UPDATE_HUD) {
            val mode = intent.getStringExtra("mode") ?: "STANDBY"
            val berryIcon = intent.getStringExtra("berryIcon") ?: "⚪"
            val berryLabel = intent.getStringExtra("berryLabel") ?: "IDLE"
            val catchIcon = intent.getStringExtra("catchIcon") ?: "⚪"
            val catchLabel = intent.getStringExtra("catchLabel") ?: "STANDBY"
            val actionIcon = intent.getStringExtra("actionIcon") ?: "⚪"
            val actionLabel = intent.getStringExtra("actionLabel") ?: "READY"

            updatePillSlots(mode, berryIcon, berryLabel, catchIcon, catchLabel, actionIcon, actionLabel)
        }

        return START_STICKY
    }

    private fun updatePillSlots(
        mode: String,
        berryIcon: String, berryLabel: String,
        catchIcon: String, catchLabel: String,
        actionIcon: String, actionLabel: String
    ) {
        val v = pillView ?: return

        v.findViewById<TextView>(R.id.pillScanText)?.text = "SCAN"
        v.findViewById<TextView>(R.id.pillModeBadge)?.text = mode.uppercase()

        v.findViewById<TextView>(R.id.pillBerryIcon)?.text = berryIcon
        v.findViewById<TextView>(R.id.pillBerryLabel)?.apply {
            text = berryLabel
            setTextColor(if (mode == "STORAGE") 0xFFF87171.toInt() else 0xFFFBBF24.toInt())
        }

        v.findViewById<TextView>(R.id.pillCatchIcon)?.text = catchIcon
        v.findViewById<TextView>(R.id.pillCatchLabel)?.apply {
            text = catchLabel
            setTextColor(if (mode == "STORAGE") 0xFFFBBF24.toInt() else 0xFF34D399.toInt())
        }

        v.findViewById<TextView>(R.id.pillActionIcon)?.text = actionIcon
        v.findViewById<TextView>(R.id.pillActionLabel)?.apply {
            text = actionLabel
            setTextColor(0xFFC084FC.toInt())
        }
    }

    private fun vibrateTap() {
        try {
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
        pillView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }
}
