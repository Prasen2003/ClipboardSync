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
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.net.URLConnection
import android.os.Environment
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.CipherInputStream
import android.util.Base64InputStream
@Composable
fun ClipboardSyncApp() {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var downloadingCount by remember { mutableStateOf(0) }
    val isDownloading = downloadingCount > 0
    var uploadingCount by remember { mutableStateOf(0) }
    val isUploading = uploadingCount > 0
    var showFileDialog by remember { mutableStateOf(false) }
    var lastText by remember { mutableStateOf("") }
    var ipAddress by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var isConnected by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(prefs.getString("server_password", "") ?: "") }
    var availableFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val ip = prefs.getString("server_ip", "") ?: ""
    var clipboardHistory by remember { mutableStateOf(listOf<String>()) }
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
    val buttonColor = Color(0xFF546E7A)
    val buttonTextColor = Color.White

    fun savePassword(pass: String) = prefs.edit().putString("server_password", pass).apply()
    fun saveIp(ip: String) = prefs.edit().putString("server_ip", ip).apply()

    fun addToHistory(text: String) {
        val newHistory = listOf(text) + history.filterNot { it == text }
        history = newHistory.take(50)
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
    ) {Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F)),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connection Settings",
                    fontSize = 18.sp,
                    color = Color.White
                )

                Text(
                    text = if (isConnected) "🟢 Connected" else "🔴 Disconnected",
                    color = if (isConnected) Color.Green else Color.Red,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                    modifier = Modifier
                        .height(56.dp)
                        .width(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = buttonTextColor
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("🔄", fontSize = 22.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = password,
                onValueChange = {
                    password = it
                    savePassword(it)
                },
                label = { Text("Server Password", color = Color.White) },
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
        }
    }
        Divider(color = Color.Gray)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let { text ->
                        lastText = text
                        addToHistory(text)
                        sendToServer(context, text, ipAddress, password)
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("\uD83D\uDCE4 Sync Text")
            }

            Button(
                onClick = {
                    fetchClipboardFromServer(
                        context = context,
                        ip = ip,
                        password = password,
                        clipboardManager = clipboardManager,
                        addToHistory = { text -> addToHistory(text) },
                        onComplete = { /* handle UI update after fetch */ }
                    )

                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("\uD83D\uDCE5 Fetch Text")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { fileLauncher.launch("*/*") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("📁 Send File")
            }

            Button(
                onClick = {
                    downloadingCount += 1
                    fetchClipboardFromServer(
                        context = context,
                        ip = ipAddress,
                        password = password,
                        clipboardManager = clipboardManager,
                        addToHistory = { text -> clipboardHistory = clipboardHistory + text },
                        onComplete = { downloadingCount = maxOf(downloadingCount - 1, 0) }
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                )
            ) {
                Text("📃 Fetch File")
            }


        }
        if (showFileDialog) {
            AlertDialog(
                onDismissRequest = { showFileDialog = false },
                title = { Text("Available Files", color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(min = 100.dp, max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (availableFiles.isEmpty()) {
                            Text("No files found", color = Color.White)
                        } else {
                            availableFiles.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            downloadingCount += 1
                                            downloadFileFromServer(context, file, ipAddress, password) {
                                                downloadingCount = maxOf(downloadingCount - 1, 0)
                                            }
                                            showFileDialog = false
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(file, color = Color.White, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileDialog = false }) {
                        Text("Close", color = Color.White)
                    }
                },
                containerColor = Color(0xFF37474F)
            )
        }

        if (isUploading) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Uploading...", fontSize = 14.sp, color = Color.White)
        }
        if (isDownloading) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Downloading...", fontSize = 14.sp, color = Color.White)
        }

        Divider(color = Color.Gray)
        Text("Clipboard History", fontSize = 18.sp, color = Color.White)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 2.dp)
        ) {

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
// --- CONFIG --- //
private const val PBKDF2_ITERATIONS = 10000
private const val KEY_LENGTH = 256 // bits
private const val SALT_SIZE = 16   // bytes
private const val IV_SIZE = 16     // bytes

// --- KEY DERIVATION --- //
private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
    val tmp = factory.generateSecret(spec)
    return SecretKeySpec(tmp.encoded, "AES")
}

// --- RANDOM GENERATORS --- //
private fun randomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return bytes
}

// --- SECURE ENCRYPTION FOR TEXT --- //
fun encryptAESCBC(text: String, password: String): String {
    val salt = randomBytes(SALT_SIZE)
    val iv = randomBytes(IV_SIZE)
    val key = deriveKey(password, salt)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
    val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    // Output: salt + iv + ciphertext (all base64 encoded)
    val out = ByteArray(salt.size + iv.size + encrypted.size)
    System.arraycopy(salt, 0, out, 0, salt.size)
    System.arraycopy(iv, 0, out, salt.size, iv.size)
    System.arraycopy(encrypted, 0, out, salt.size + iv.size, encrypted.size)
    return Base64.encodeToString(out, Base64.NO_WRAP)
}

fun decryptAESCBC(encrypted: String, password: String): String {
    val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
    val salt = decoded.copyOfRange(0, SALT_SIZE)
    val iv = decoded.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
    val ciphertext = decoded.copyOfRange(SALT_SIZE + IV_SIZE, decoded.size)
    val key = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
    val decrypted = cipher.doFinal(ciphertext)
    return String(decrypted, Charsets.UTF_8)
}

// --- SECURE ENCRYPTION FOR FILES (BYTES) --- //
fun encryptFileBytesCBC(data: ByteArray, password: String): ByteArray {
    val salt = randomBytes(SALT_SIZE)
    val iv = randomBytes(IV_SIZE)
    val key = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
    val encrypted = cipher.doFinal(data)
    // Output: salt + iv + ciphertext
    val out = ByteArray(salt.size + iv.size + encrypted.size)
    System.arraycopy(salt, 0, out, 0, salt.size)
    System.arraycopy(iv, 0, out, salt.size, iv.size)
    System.arraycopy(encrypted, 0, out, salt.size + iv.size, encrypted.size)
    return out
}

fun decryptFileBytesCBC(data: ByteArray, password: String): ByteArray {
    val salt = data.copyOfRange(0, SALT_SIZE)
    val iv = data.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
    val ciphertext = data.copyOfRange(SALT_SIZE + IV_SIZE, data.size)
    val key = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
    return cipher.doFinal(ciphertext)
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

    val fileBytes = tempFile.readBytes()
    val encryptedBytes = encryptFileBytesCBC(fileBytes, password)
    tempFile.writeBytes(encryptedBytes)

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

fun saveFileToDownloads(
    context: Context,
    filename: String,
    mimeType: String,
    inputStream: InputStream,
    size: Long
): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fileUri = resolver.insert(collection, values)

        if (fileUri != null) {
            resolver.openOutputStream(fileUri)?.use { output ->
                inputStream.copyTo(output)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(fileUri, values, null, null)
            return fileUri.toString()
        } else {
            null
        }
    } else {
        // Below API 29
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = File(downloadsDir, filename)
        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }
}

fun downloadFileFromServer(
    context: Context,
    filename: String,
    ip: String,
    password: String,
    onComplete: () -> Unit = {}
) {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        onComplete()
        return
    }

    val client = OkHttpClient()
    val request = Request.Builder()
        .url("http://$ip:8000/download/$filename")
        .addHeader("X-Auth-Token", password)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                onComplete()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful || response.body == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Download failed: ${response.code}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                return
            }
            try {
                val encryptedStream = response.body!!.byteStream()
                // --- Patch: decode base64 on the fly ---
                val b64Stream = Base64InputStream(encryptedStream, android.util.Base64.DEFAULT)

                // Read salt + iv headers (first 32 bytes) from the decoded stream
                val header = ByteArray(SALT_SIZE + IV_SIZE)
                var read = 0
                while (read < header.size) {
                    val r = b64Stream.read(header, read, header.size - read)
                    if (r == -1) throw IOException("Unexpected EOF in header")
                    read += r
                }
                val salt = header.copyOfRange(0, SALT_SIZE)
                val iv = header.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                val decryptedStream = CipherInputStream(b64Stream, cipher)

                val mimeType = URLConnection.guessContentTypeFromName(filename)
                    ?: "application/octet-stream"

                val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloadsQAndAbove(context, filename, mimeType, decryptedStream, -1)
                } else {
                    saveToDownloadsLegacy(context, filename, decryptedStream)
                }

                Handler(Looper.getMainLooper()).post {
                    if (savedPath != null) {
                        Toast.makeText(context, "✅ Downloaded to: $savedPath", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "❌ Failed to save file", Toast.LENGTH_LONG).show()
                    }
                    onComplete()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ File decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                    onComplete()
                }
            }
        }
    })
}

private fun deleteAllDownloadsByNameBlocking(context: Context, fileName: String, timeoutMs: Long = 2000) {
    // Only call this for API 29+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val startTime = System.currentTimeMillis()
    while (true) {
        var found = false
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(fileName),
            null
        )
        cursor?.use {
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                found = true
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                resolver.delete(uri, null, null)
            }
        }
        if (!found) break
        if (System.currentTimeMillis() - startTime > timeoutMs) break
        Thread.sleep(100)
    }
}

private fun saveToDownloadsQAndAbove(
    context: Context,
    filename: String,
    mimeType: String,
    inputStream: InputStream,
    size: Long
): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    try {
        // Robust delete before insert, blocking until gone or timeout
        deleteAllDownloadsByNameBlocking(context, filename)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (size > 0) put(MediaStore.Downloads.SIZE, size)
        }
        val fileUri = resolver.insert(collection, values)
        if (fileUri != null) {
            resolver.openOutputStream(fileUri)?.use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(fileUri, values, null, null)
            return fileUri.toString()
        } else {
            // Last resort: MediaStore may still have a ghost file; check if file now exists
            val cursor = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(filename),
                null
            )
            val exists = cursor?.moveToFirst() == true
            cursor?.close()
            return if (exists) "mediastore://found/$filename" else null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        // Last resort: MediaStore error, but maybe file is present
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(filename),
            null
        )
        val exists = cursor?.moveToFirst() == true
        cursor?.close()
        return if (exists) "mediastore://found/$filename" else null
    }
}


private fun saveToDownloadsLegacy(
    context: Context,
    filename: String,
    inputStream: InputStream
): String? {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists()) downloadsDir.mkdirs()
    val outFile = File(downloadsDir, filename)
    if (outFile.exists()) outFile.delete()
    return try {
        inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                }
            }
        }
        outFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun sendToServer(context: Context, text: String, ip: String, password: String, onStatusChange: (Boolean) -> Unit = {})
 {
    if (ip.isBlank()) {
        Toast.makeText(context, "❗ No IP address configured", Toast.LENGTH_SHORT).show()
        onStatusChange(false)
        return
    }

    val client = OkHttpClient()
     val encryptedText = encryptAESCBC(text, password)
     val requestBody = FormBody.Builder().add("clipboard", encryptedText).build()

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
    password: String,
    clipboardManager: ClipboardManager,
    addToHistory: (String) -> Unit,
    onComplete: () -> Unit
) {
    val request = Request.Builder()
        .url("http://$ip:8000/clipboard")
        .get()
        .addHeader("X-Auth-Token", password)
        .build()

    val client = OkHttpClient()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Fetch failed: ${e.message}", Toast.LENGTH_LONG).show()
                onComplete()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "⚠️ Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                    return
                }

                val bodyString = it.body?.string()
                val json = JSONObject(bodyString)
                val isFile = json.optBoolean("is_file", false)

                if (isFile) {
                    val filename = json.optString("filename", "fetched_file")
                    downloadFileFromClipboard(context, ip, password, filename, onComplete)
                } else {
                    val encryptedText = json.getString("clipboard")
                    val text = decryptAESCBC(encryptedText, password)
                    Handler(Looper.getMainLooper()).post {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("label", text))
                        addToHistory(text)
                        Toast.makeText(context, "📋 Clipboard updated", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                }
            }
        }
    })
}
fun downloadFileFromClipboard(
    context: Context,
    ip: String,
    password: String,
    filename: String,
    onComplete: () -> Unit
) {
    val request = Request.Builder()
        .url("http://$ip:8000/download_clipboard_file")
        .get()
        .addHeader("X-Auth-Token", password)
        .build()
    val client = OkHttpClient()
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                onComplete()
            }
        }
        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful || response.body == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Download failed: ${response.code}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                return
            }
            try {
                val encryptedStream = response.body!!.byteStream()
                // --- Patch: decode base64 on the fly ---
                val b64Stream = Base64InputStream(encryptedStream, android.util.Base64.DEFAULT)

                // Read salt + iv headers (first 32 bytes) from the decoded stream
                val header = ByteArray(SALT_SIZE + IV_SIZE)
                var read = 0
                while (read < header.size) {
                    val r = b64Stream.read(header, read, header.size - read)
                    if (r == -1) throw IOException("Unexpected EOF in header")
                    read += r
                }
                val salt = header.copyOfRange(0, SALT_SIZE)
                val iv = header.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                val decryptedStream = CipherInputStream(b64Stream, cipher)

                val mimeType = URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"
                val savedPath: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloadsQAndAbove(context, filename, mimeType, decryptedStream, -1)
                } else {
                    saveToDownloadsLegacy(context, filename, decryptedStream)
                }
                Handler(Looper.getMainLooper()).post {
                    if (savedPath != null) {
                        Toast.makeText(context, "✅ File downloaded: $filename", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "❌ Failed to save file", Toast.LENGTH_LONG).show()
                    }
                    onComplete()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ File decryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                    onComplete()
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

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        if (intent.getBooleanExtra("syncNow", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                syncClipboardFromActivity()
                intent.removeExtra("syncNow")
            }, 50)
        }

        if (intent.getBooleanExtra("fetchNow", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val password = prefs.getString("password", "") ?: ""

                fetchClipboardFromServer(
                    context = this,
                    ip = prefs.getString("server_ip", "") ?: "",
                    password = password,
                    clipboardManager = clipboardManager,
                    addToHistory = { _ -> }, // assuming you have this function
                    onComplete = {
                        Handler(Looper.getMainLooper()).postDelayed({
                            moveTaskToBack(true)
                        }, 1000)
                    }
                )
                intent.removeExtra("fetchNow")
                Handler(Looper.getMainLooper()).postDelayed({
                    moveTaskToBack(true)
                }, 1000) // ✅ same delay as sync
            }, 50)
        }
    }



    private fun syncClipboardFromActivity() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val ip = prefs.getString("server_ip", "") ?: ""

        if (!text.isNullOrEmpty()) {
            val password = prefs.getString("server_password", "") ?: ""
            sendToServer(this, text, ip, password)
        } else {
            Toast.makeText(this, "\uD83D\uDCCB Clipboard is empty", Toast.LENGTH_SHORT).show()
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

        Toast.makeText(this, "\u2705 Overlay service started", Toast.LENGTH_SHORT).show()
    }
}
