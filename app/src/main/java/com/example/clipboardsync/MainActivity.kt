package com.example.clipboardsync

import android.app.*
import android.content.*
import android.net.*
import android.net.nsd.*
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
@Composable
fun ClipboardSyncApp() {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)


    var lastText by remember { mutableStateOf("") }
    var ipAddress by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var isConnected by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(prefs.getString("server_password", "") ?: "") }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            uploadFileToServer(context, it, ipAddress, password)
        }
    }
    fun savePassword(pass: String) {
        prefs.edit().putString("server_password", pass).apply()
    }
    fun saveIp(ip: String) {
        prefs.edit().putString("server_ip", ip).apply()
    }

    fun addToHistory(text: String) {
        val newHistory = listOf(text) + history.filterNot { it == text }
        history = newHistory.take(20)
        val json = JSONArray(newHistory)
        prefs.edit().putString("clipboard_history", json.toString()).apply()
    }

    fun deleteFromHistory(item: String) {
        history = history.filterNot { it == item }
        val json = JSONArray(history)
        prefs.edit().putString("clipboard_history", json.toString()).apply()
    }

    // 🔁 Heartbeat ping
    LaunchedEffect(ipAddress) {
        while (true) {
            pingServer(context, ipAddress, password) { success ->
                isConnected = success
            }
            delay(5000)
        }
    }

    // Discover service on first launch
    LaunchedEffect(Unit) {
        discoverService(context) { ip ->
            ipAddress = ip
            saveIp(ip)
        }
        val initialText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        if (!initialText.isNullOrBlank()) {
            lastText = initialText
            addToHistory(initialText)
        }
    }

    // Clipboard listener
    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            val text = clip?.getItemAt(0)?.text.toString()
            if (text.isNotBlank() && text != lastText) {
                lastText = text
                addToHistory(text)
                sendToServer(context, text, ipAddress, password)
            }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = ipAddress,
                onValueChange = {
                    ipAddress = it
                    saveIp(it)
                },
                label = { Text("Server IP Address") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            Button(onClick = {
                discoverService(context) { ip ->
                    ipAddress = ip
                    saveIp(ip)
                    Toast.makeText(context, "🔄 IP updated to $ip", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("🔄")
            }
        }

        // ✅ Live connection status
        Text(
            text = if (isConnected) "🟢 Connected" else "🔴 Disconnected",
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontSize = 16.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val clip = clipboardManager.primaryClip
                val text = clip?.getItemAt(0)?.text.toString()
                if (text != lastText) {
                    lastText = text
                    addToHistory(text)
                    sendToServer(context, text, ipAddress, password)
                }
            }) {
                Text("📤 Sync Clipboard Now")
            }

            Button(onClick = {
                fetchClipboardFromServer(
                    context,
                    ipAddress,
                    onFetched = { if (!it.isNullOrBlank()) addToHistory(it) }
                )
            }) {
                Text("📥 Fetch from PC")
            }
        }
        Button(onClick = {
            fileLauncher.launch("*/*") // any file type
        }) {
            Text("📁 Send File")
        }
        TextField(
            value = password,
            onValueChange = {
                password = it
                savePassword(it)
            },
            label = { Text("Server Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Clipboard History", fontSize = 16.sp)

        var selectedItem by remember { mutableStateOf<String?>(null) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedItem = item },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.take(50).replace("\n", " "),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        IconButton(onClick = {
                            val clip = ClipData.newPlainText("Copied", item)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "📋 Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("📋")
                        }
                        IconButton(onClick = { deleteFromHistory(item) }) {
                            Text("🗑️")
                        }
                    }
                }
            }
        }

        if (selectedItem != null) {
            val scrollState = rememberScrollState()
            AlertDialog(
                onDismissRequest = { selectedItem = null },
                title = { Text("Clipboard Entry") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(min = 100.dp, max = 400.dp)
                            .verticalScroll(scrollState)
                            .padding(4.dp)
                    ) {
                        Text(text = selectedItem!!, fontSize = 14.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedItem = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

fun pingServer(context: Context, ip: String, password: String, onResult: (Boolean) -> Unit) {
    if (ip.isBlank()) {
        onResult(false)
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://$ip:8000/ping")
                .get()
                .addHeader("X-Auth-Token", password)
                .build()

            val response = client.newCall(request).execute()
            onResult(response.isSuccessful)
        } catch (e: Exception) {
            onResult(false)
        }
    }
}

fun loadHistory(prefs: SharedPreferences): List<String> {
    val json = prefs.getString("clipboard_history", "[]")
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i -> arr.getString(i) }
    } catch (e: Exception) {
        emptyList()
    }
}

fun discoverService(context: Context, onFound: (String) -> Unit) {
    val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val serviceType = "_http._tcp."
    val serviceNamePrefix = "ClipboardSyncServer"
    lateinit var discoveryListener: NsdManager.DiscoveryListener

    discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceName.contains(serviceNamePrefix)) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host.hostAddress
                        if (host != null) {
                            onFound(host)
                            nsdManager.stopServiceDiscovery(discoveryListener)
                        }
                    }
                })
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
}

fun uploadFileToServer(context: Context, uri: Uri, ip: String, password: String) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        return
    }

    val contentResolver = context.contentResolver
    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
    val inputStream = contentResolver.openInputStream(uri) ?: return

    val tempFile = File.createTempFile("upload", fileName, context.cacheDir)
    inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }

    val fileBody = tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
    val multipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", fileName, fileBody)
        .build()

    val request = Request.Builder()
        .url("http://$ip:8000/upload")
        .post(multipartBody)
        .addHeader("X-Auth-Token", password)
        .build()

    val client = OkHttpClient()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            Handler(Looper.getMainLooper()).post {
                if (response.isSuccessful) {
                    Toast.makeText(context, "✅ File uploaded successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "⚠️ Upload failed: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    })
}

fun sendToServer(context: Context, text: String, ip: String, password: String, onStatusChange: (Boolean) -> Unit = {})
 {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        onStatusChange(false)
        return
    }

    val client = OkHttpClient()
    val requestBody = FormBody.Builder().add("clipboard", text).build()
    val request = Request.Builder()
        .url("http://$ip:8000/clipboard")
        .post(requestBody)
        .addHeader("X-Auth-Token", password)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                onStatusChange(false) // Notify failure
                Toast.makeText(context, "❌ Failed to sync: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            Handler(Looper.getMainLooper()).post {
                val isSuccess = response.isSuccessful
                onStatusChange(isSuccess)  // Pass status as Boolean

                if (isSuccess) {
                    Toast.makeText(context, "✅ Clipboard synced", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    })
}

fun fetchClipboardFromServer(
    context: Context,
    ip: String,
    onFetched: (String?) -> Unit = {},
    onStatusChange: (Boolean) -> Unit = {}
) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        onStatusChange(false)
        return
    }

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val password = prefs.getString("server_password", "") ?: ""

    val client = OkHttpClient()
    val request = Request.Builder()
        .url("http://$ip:8000/clipboard")
        .get()
        .addHeader("X-Auth-Token", password)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                onStatusChange(false)
                Toast.makeText(context, "❌ Failed to fetch: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                onFetched(null)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.body?.string()
            Handler(Looper.getMainLooper()).post {
                val isSuccess = response.isSuccessful
                onStatusChange(isSuccess)

                if (isSuccess && !result.isNullOrBlank()) {
                    try {
                        val text = JSONObject(result).getString("clipboard")
                        if (text.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Clipboard", text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "📥 Clipboard updated", Toast.LENGTH_SHORT).show()
                            onFetched(text)
                        } else {
                            Toast.makeText(context, "⚠️ Empty clipboard data", Toast.LENGTH_SHORT).show()
                            onFetched(null)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "⚠️ Parse error", Toast.LENGTH_SHORT).show()
                        onFetched(null)
                    }
                } else {
                    Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                    onFetched(null)
                }
            }
        }
    })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClipboardSyncApp() }
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
        val ip = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("server_ip", "") ?: ""

        if (!text.isNullOrEmpty()) {
            val password = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("server_password", "") ?: ""
            sendToServer(this, text, ip, password)

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
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        Toast.makeText(this, "✅ Overlay service started", Toast.LENGTH_SHORT).show()
    }
}
