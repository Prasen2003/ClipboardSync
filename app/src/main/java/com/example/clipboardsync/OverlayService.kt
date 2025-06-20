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
    private lateinit var syncView: AppCompatImageView
    private lateinit var fetchView: AppCompatImageView
    private lateinit var syncParams: WindowManager.LayoutParams
    private lateinit var fetchParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

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

        val notification = NotificationCompat.Builder(this, "clipboard_channel")
            .setContentTitle("Clipboard Sync Running")
            .setContentText("Tap bubble or use notification buttons")
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .addAction(R.drawable.ic_clipboard_sync, "Sync Clipboard", syncPendingIntent)
            .addAction(R.drawable.ic_clipboard_sync, "Fetch Clipboard", fetchPendingIntent)
            .build()

        startForeground(1, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val iconWidth = 150  // estimated icon width in pixels
        val iconHeight = 150 // estimated icon height in pixels
        val offsetFromRight = 30  // how far inward from the right edge

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

        syncView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_sync)
            isClickable = true
        }

        fetchView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_sync)
            isClickable = true
            rotation = 180f // visually points downward
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
                    windowManager.updateViewLayout(syncView, syncParams)
                    windowManager.updateViewLayout(fetchView, fetchParams)
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

        syncView.setOnTouchListener(sharedTouchListener)
        fetchView.setOnTouchListener(sharedTouchListener)

        windowManager.addView(syncView, syncParams)
        windowManager.addView(fetchView, fetchParams)
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

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized) {
            if (::syncView.isInitialized) windowManager.removeView(syncView)
            if (::fetchView.isInitialized) windowManager.removeView(fetchView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
