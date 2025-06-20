package com.example.clipboardsync

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var syncView: AppCompatImageView? = null
    private var fetchView: AppCompatImageView? = null
    private lateinit var syncParams: WindowManager.LayoutParams
    private lateinit var fetchParams: WindowManager.LayoutParams

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("overlay_action")) {
            "show" -> showOverlayBubble()
            "hide" -> hideOverlayBubble()
            else -> { /* no-op, don't show overlays unless explicitly requested */ }
        }
        startForeground(1, buildNotification())
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val iconWidth = 150
        val iconHeight = 150
        val offsetFromRight = 30

        val startX = screenWidth - iconWidth - offsetFromRight
        val startY = screenHeight / 2 - iconHeight / 2

        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val baseFormat = PixelFormat.TRANSLUCENT

        syncParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            baseType,
            baseFlags,
            baseFormat
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        fetchParams = WindowManager.LayoutParams(
            syncParams.width,
            syncParams.height,
            syncParams.type,
            syncParams.flags,
            syncParams.format
        ).apply {
            gravity = syncParams.gravity
            x = syncParams.x
            y = syncParams.y + 150
        }

        // --- MOVE THIS TO THE END ---
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("overlay_enabled", true)) {
            showOverlayBubble()
        } else {
            hideOverlayBubble()
        }
    }

    private fun showOverlayBubble() {
        if (syncView != null && fetchView != null) return // Already shown

        syncView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_sync)
            isClickable = true
        }
        fetchView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_sync)
            isClickable = true
            rotation = 180f
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val sharedTouchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = syncParams.x
                    initialY = syncParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    syncParams.x = initialX + dx
                    syncParams.y = initialY + dy
                    fetchParams.x = syncParams.x
                    fetchParams.y = syncParams.y + 150
                    syncView?.let { windowManager.updateViewLayout(it, syncParams) }
                    fetchView?.let { windowManager.updateViewLayout(it, fetchParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = Math.abs(event.rawX - initialTouchX)
                    val movedY = Math.abs(event.rawY - initialTouchY)
                    if (movedX < 10 && movedY < 10) {
                        if (view == syncView) {
                            syncClipboard()
                        } else if (view == fetchView) {
                            fetchClipboard()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        syncView?.setOnTouchListener(sharedTouchListener)
        fetchView?.setOnTouchListener(sharedTouchListener)

        try {
            syncView?.let { windowManager.addView(it, syncParams) }
        } catch (_: Exception) { }
        try {
            fetchView?.let { windowManager.addView(it, fetchParams) }
        } catch (_: Exception) { }
    }

    private fun hideOverlayBubble() {
        syncView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
            syncView = null
        }
        fetchView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
            fetchView = null
        }
    }

    private fun syncClipboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("syncNow", true)
        }
        startActivity(intent)
    }

    private fun fetchClipboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("fetchNow", true)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "clipboard_channel",
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // --- Add PendingIntents for notification actions ---
        val syncIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("syncNow", true)
        }
        val syncPendingIntent = PendingIntent.getActivity(
            this, 100, syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        )

        val fetchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("fetchNow", true)
        }
        val fetchPendingIntent = PendingIntent.getActivity(
            this, 101, fetchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        )

        return NotificationCompat.Builder(this, "clipboard_channel")
            .setContentTitle("Clipboard Sync Running")
            .setContentText("Tap bubble or use notification buttons")
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .addAction(R.drawable.ic_clipboard_sync, "Sync Clipboard", syncPendingIntent)
            .addAction(R.drawable.ic_clipboard_sync, "Fetch Clipboard", fetchPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlayBubble()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}