package com.example.clipboardsync

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.NotificationCompat
import okhttp3.*
import java.io.IOException

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: AppCompatImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "clipboard_channel")
            .setContentTitle("Clipboard Sync Running")
            .setContentText("Tap bubble to sync clipboard")
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .build()

        startForeground(1, notification)

        // Layout params for the bubble
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        overlayView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_sync)
            isClickable = true
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if ((event.rawX - initialTouchX).let { Math.abs(it) } < 10 &&
                        (event.rawY - initialTouchY).let { Math.abs(it) } < 10) {
                        syncClipboard()
                    }
                    true
                }

                else -> false
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(overlayView, layoutParams)
    }

    private fun syncClipboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("syncNow", true)
        }
        startActivity(intent)
    }


    private fun sendToServer(context: Context, text: String) {
        val client = OkHttpClient()
        val requestBody = FormBody.Builder()
            .add("clipboard", text)
            .build()

        val request = Request.Builder()
            .url("http://192.168.0.171:8000/clipboard") // Update IP if needed
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ Sync failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                Handler(Looper.getMainLooper()).post {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "✅ Clipboard synced", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
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
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
