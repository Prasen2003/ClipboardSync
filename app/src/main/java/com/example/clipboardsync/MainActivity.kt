package com.example.clipboardsync

import android.content.*
import android.net.nsd.*
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import android.webkit.MimeTypeMap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.foundation.*
import androidx.compose.material3.TextFieldDefaults
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation


@Composable
fun ClipboardSyncApp() {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var uploadingCount by remember { mutableStateOf(0) }
    val isUploading = uploadingCount > 0

    var lastText by remember { mutableStateOf("") }
    var ipAddress by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var isConnected by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(prefs.getString("server_password", "") ?: "") }
    var availableFiles by remember { mutableStateOf<List<String>>(emptyList()) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            uploadFileToServer(context, it, ipAddress, password) { uploading ->
                uploadingCount = when {
                    uploading -> uploadingCount + 1
                    uploadingCount > 0 -> uploadingCount - 1
                    else -> 0
                }
            }
        }
    }
    val buttonColor = Color(0xFF546E7A) // Muted Blue-Grey
    val buttonTextColor = Color.White

    fun savePassword(pass: String) = prefs.edit().putString("server_password", pass).apply()
    fun saveIp(ip: String) = prefs.edit().putString("server_ip", ip).apply()

    fun addToHistory(text: String) {
        val newHistory = listOf(text) + history.filterNot { it == text }
        history = newHistory.take(20)
        prefs.edit().putString("clipboard_history", JSONArray(newHistory).toString()).apply()
    }

    fun deleteFromHistory(item: String) {
        history = history.filterNot { it == item }
        prefs.edit().putString("clipboard_history", JSONArray(history).toString()).apply()
    }

    LaunchedEffect(ipAddress) {
        while (true) {
            pingServer(context, ipAddress, password) { isConnected = it }
            delay(5000)
        }
    }

    LaunchedEffect(Unit) {
        discoverService(context) {
            ipAddress = it
            saveIp(it)
        }
        clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let {
            if (it.isNotBlank()) {
                lastText = it
                addToHistory(it)
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboardManager.primaryClip?.getItemAt(0)?.text.toString()
            if (text.isNotBlank() && text != lastText) {
                lastText = text
                addToHistory(text)
                sendToServer(context, text, ipAddress, password)
            }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
    }

    var selectedItem by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF263238)) // dark blue-grey
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text("Connection Settings", fontSize = 18.sp, color = Color.White)

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
                label = { Text("Server IP Address", color = Color.White) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF455A64),
                    unfocusedContainerColor = Color(0xFF455A64),
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White,
                    focusedIndicatorColor = Color.White,
                    unfocusedIndicatorColor = Color.White,
                    cursorColor = Color.White
                )
            )
            Button(
                onClick = {
                    discoverService(context) {
                        ipAddress = it
                        saveIp(it)
                        Toast.makeText(context, "🔄 IP updated to $it", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("🔄")
            }
        }

        TextField(
            value = password,
            onValueChange = {
                password = it
                savePassword(it)
            },
            label = { Text("Server Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = Color.White
                    )
                }
            },
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF455A64),
                unfocusedContainerColor = Color(0xFF455A64),
                focusedLabelColor = Color.White,
                unfocusedLabelColor = Color.White,
                focusedIndicatorColor = Color.White,
                unfocusedIndicatorColor = Color.White,
                cursorColor = Color.White
            )

        )


        Text(
            text = if (isConnected) "🟢 Connected" else "🔴 Disconnected",
            color = if (isConnected) Color.Green else Color.Red,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Divider(color = Color.Gray)

        Text("Clipboard Actions", fontSize = 18.sp, color = Color.White)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let { text ->
                        if (text != lastText) {
                            lastText = text
                            addToHistory(text)
                            sendToServer(context, text, ipAddress, password)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("📤 Sync Clipboard")
            }

            Button(
                onClick = {
                    fetchClipboardFromServer(
                        context,
                        ipAddress,
                        onFetched = { if (!it.isNullOrBlank()) addToHistory(it) }
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("📥 Fetch from PC")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { fileLauncher.launch("*/*") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp), // add spacing on the end (right)
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Text("📁 Send File")
                }

                Button(
                    onClick = {
                        fetchFileListFromServer(context, ipAddress, password) { files ->
                            availableFiles = files
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp), // add spacing on the start (left)
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Text("📃 Fetch Files")
                }
            }


            if (isUploading) {
                Spacer(modifier = Modifier.width(8.dp))
                Text("Uploading...", fontSize = 14.sp, color = Color.White)
            }
        }

        Divider(color = Color.Gray)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            item {
                Text("Available Files", fontSize = 18.sp, color = Color.White)
            }

            items(availableFiles) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            downloadFileFromServer(context, file, ipAddress, password)
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(file, color = Color.White)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Clipboard History", fontSize = 18.sp, color = Color.White)
            }

            items(history) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { selectedItem = item },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.take(50).replace("\n", " "),
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        IconButton(onClick = {
                            val clip = ClipData.newPlainText("Copied", item)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "📋 Copied to clipboard", Toast.LENGTH_SHORT)
                                .show()
                        }) {
                            Text("📋", color = Color.White)
                        }
                        IconButton(onClick = { deleteFromHistory(item) }) {
                            Text("🗑️", color = Color.White)
                        }
                    }
                }
            }
        }
    }

        // Expanded History View Dialog
    selectedItem?.let {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text("Clipboard Entry", color = Color.White) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(min = 100.dp, max = 400.dp)
                        .verticalScroll(scrollState)
                        .padding(4.dp)
                ) {
                    Text(it, fontSize = 14.sp, color = Color.White)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItem = null }) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = Color(0xFF37474F)
        )
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

fun uploadFileToServer(context: Context,
                       uri: Uri,
                       ip: String,
                       password: String,
                       onUploadingChanged: (Boolean) -> Unit) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        return
    }
    onUploadingChanged(true)
    val contentResolver = context.contentResolver

    // Step 1: Get MIME type and map to extension
    val mimeType = contentResolver.getType(uri)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"

    // Step 2: Try to get the original filename or fallback
    var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"

    // Step 3: Ensure filename has correct extension
    if (!fileName.contains('.')) {
        fileName += ".$extension"
    }

    val inputStream = contentResolver.openInputStream(uri) ?: return
    val tempFile = File.createTempFile("upload", null, context.cacheDir)
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
                onUploadingChanged(false) // Upload ended with failure
            }
        }

        override fun onResponse(call: Call, response: Response) {
            Handler(Looper.getMainLooper()).post {
                if (response.isSuccessful) {
                    Toast.makeText(context, "✅ File uploaded successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "⚠️ Upload failed: ${response.code}", Toast.LENGTH_SHORT).show()
                }
                onUploadingChanged(false) // Upload ended (success or failure)
            }
        }
    })
}
fun fetchFileListFromServer(
    context: Context,
    ip: String,
    password: String,
    onResult: (List<String>) -> Unit
) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        onResult(emptyList())
        return
    }

    val client = OkHttpClient()
    val request = Request.Builder()
        .url("http://$ip:8000/list-files")
        .addHeader("X-Auth-Token", password)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Failed to get file list", Toast.LENGTH_SHORT).show()
                onResult(emptyList())
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                    onResult(emptyList())
                }
                return
            }

            val body = response.body?.string()
            val fileList = try {
                val json = JSONObject(body ?: "{}")
                val filesArray = json.getJSONArray("files")
                List(filesArray.length()) { i -> filesArray.getString(i) }
            } catch (e: Exception) {
                emptyList()
            }

            Handler(Looper.getMainLooper()).post {
                onResult(fileList)
            }
        }
    })
}

fun downloadFileFromServer(
    context: Context,
    filename: String,
    ip: String,
    password: String
) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        return
    }

    val client = OkHttpClient.Builder()
        .cache(null)  // Disable cache
        .build()

    val request = Request.Builder()
        .url("http://$ip:8000/download/$filename")
        .addHeader("X-Auth-Token", password)
        .addHeader("Cache-Control", "no-cache")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Download failed: ${response.code}", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val inputStream = response.body?.byteStream()
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            val outFile = File(downloadsDir, filename)

            // Delete old file if it exists (even if partially)
            if (outFile.exists()) outFile.delete()

            inputStream?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "✅ Downloaded to: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
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
