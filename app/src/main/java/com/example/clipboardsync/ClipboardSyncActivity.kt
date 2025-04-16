package com.example.clipboardsync

import android.app.Activity
import android.content.*
import android.os.*
import android.widget.Toast
import okhttp3.*
import java.io.IOException

class ClipboardSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        syncClipboard()
    }

    private fun syncClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()

        if (!text.isNullOrEmpty()) {
            sendToServer(text)
        } else {
            Toast.makeText(this, "📋 Clipboard is empty", Toast.LENGTH_SHORT).show()
            finishAfterDelay()
        }
    }

    private fun sendToServer(text: String) {
        val client = OkHttpClient()
        val requestBody = FormBody.Builder()
            .add("clipboard", text)
            .build()

        val request = Request.Builder()
            .url("http://192.168.0.171:8000/clipboard") // replace with your server IP
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ClipboardSyncActivity, "❌ Sync failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    finishAfterDelay()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ClipboardSyncActivity, "✅ Clipboard synced", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ClipboardSyncActivity, "⚠️ Server error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    finishAfterDelay()
                }
            }
        })
    }

    private fun finishAfterDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 800)
    }
}
