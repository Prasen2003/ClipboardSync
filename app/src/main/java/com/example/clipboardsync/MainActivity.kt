package com.example.clipboardsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.*
import java.io.IOException

// ⬇️ Composable UI
@Composable
fun ClipboardSyncApp() {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var lastText by remember { mutableStateOf("") }

    // Load and remember the IP address from SharedPreferences
    var ipAddress by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("server_ip", "192.168.0.171") ?: ""
        )
    }

    // Save IP on change
    fun saveIp(ip: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("server_ip", ip)
            .apply()
    }

    // Clipboard auto-sync listener
    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            val text = clip?.getItemAt(0)?.text.toString()
            if (text != lastText) {
                lastText = text
                sendToServer(context, text, ipAddress)
            }
        }

        clipboardManager.addPrimaryClipChangedListener(listener)

        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }

    // UI Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextField(
            value = ipAddress,
            onValueChange = {
                ipAddress = it
                saveIp(it)
            },
            label = { Text("Server IP Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            val clip = clipboardManager.primaryClip
            val text = clip?.getItemAt(0)?.text.toString()
            if (text != lastText) {
                lastText = text
                sendToServer(context, text, ipAddress)
            }
        }) {
            Text("📤 Sync Clipboard Now")
        }

        Button(onClick = {
            fetchClipboardFromServer(context, ipAddress)
        }) {
            Text("📥 Fetch from PC")
        }

        Text("Last Copied: $lastText", fontSize = 14.sp)
    }
}

// ⬇️ Send to FastAPI server
fun sendToServer(context: Context, text: String, ip: String) {
    val client = OkHttpClient()
    val requestBody = FormBody.Builder()
        .add("clipboard", text)
        .build()

    val request = Request.Builder()
        .url("http://$ip:8000/clipboard")
        .post(requestBody)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Failed to sync: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

// ⬇️ Fetch from FastAPI server
fun fetchClipboardFromServer(context: Context, ip: String) {
    val client = OkHttpClient()

    val request = Request.Builder()
        .url("http://$ip:8000/clipboard")
        .get()
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Failed to fetch: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.body?.string()
            if (response.isSuccessful && result != null) {
                val json = Regex("\"clipboard\"\\s*:\\s*\"(.*?)\"").find(result)
                val text = json?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")

                if (!text.isNullOrEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Synced", text)
                    clipboard.setPrimaryClip(clip)

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "✅ Clipboard fetched", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_LONG).show()
                }
            }
        }
    })
}

// ⬇️ MainActivity
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ClipboardSyncApp()
        }

        checkOverlayPermission()
    }

    override fun onResume() {
        super.onResume()

        if (intent.getBooleanExtra("syncNow", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                syncClipboardFromActivity()
                intent.removeExtra("syncNow")
            }, 50)
        }
    }

    private fun syncClipboardFromActivity() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()

        val ip = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("server_ip", "192.168.0.171") ?: "192.168.0.171"

        if (!text.isNullOrEmpty()) {
            sendToServer(this, text, ip)
        } else {
            Toast.makeText(this, "📋 Clipboard is empty", Toast.LENGTH_SHORT).show()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            moveTaskToBack(true)
        }, 1000)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startOverlayService()
            }
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "✅ Overlay service started", Toast.LENGTH_SHORT).show()
    }
}
