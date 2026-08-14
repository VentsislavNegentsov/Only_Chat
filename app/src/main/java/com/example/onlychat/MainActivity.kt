package com.example.onlychat

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

// ============================================================================
// DATA MODELS & ENUMS
// ============================================================================

enum class MessageStatus { SENT, DELIVERED, READ }

data class PeerDevice(
    val endpointId: String,
    val name: String,
    val isOnlyChatActive: Boolean = true,
    val signalQuality: String = "📶 Strong"
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val isFromMe: Boolean,
    var status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val base64Image: String? = null,
    val progress: Float? = null
)

// ============================================================================
// UTILITY OBJECTS & STORAGE HELPER
// ============================================================================

object ChatStorageHelper {
    private const val FILE_NAME = "chat_history_v2.json"

    fun saveHistory(context: Context, history: Map<String, List<ChatMessage>>) {
        try {
            val rootObject = JSONObject()
            for ((peerName, messages) in history) {
                val array = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject().apply {
                        put("id", msg.id)
                        put("text", msg.text)
                        put("isFromMe", msg.isFromMe)
                        put("status", msg.status.name)
                        put("timestamp", msg.timestamp)
                        put("base64Image", msg.base64Image ?: "")
                        put("progress", msg.progress ?: 1.0)
                    }
                    array.put(obj)
                }
                rootObject.put(peerName, array)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(rootObject.toString(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadHistory(context: Context): Map<String, List<ChatMessage>> {
        val historyMap = mutableMapOf<String, List<ChatMessage>>()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return historyMap

            val content = file.readText(StandardCharsets.UTF_8)
            if (content.isBlank()) return historyMap

            val rootObject = JSONObject(content)
            val keys = rootObject.keys()

            while (keys.hasNext()) {
                val peerName = keys.next()
                val array = rootObject.getJSONArray(peerName)
                val msgList = mutableListOf<ChatMessage>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val imgStr = obj.optString("base64Image", "")
                    val msg = ChatMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        text = obj.optString("text", ""),
                        isFromMe = obj.optBoolean("isFromMe", true),
                        status = try { MessageStatus.valueOf(obj.optString("status", "SENT")) } catch (e: Exception) { MessageStatus.SENT },
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        base64Image = if (imgStr.isNotEmpty()) imgStr else null,
                        progress = obj.optDouble("progress", 1.0).toFloat()
                    )
                    msgList.add(msg)
                }
                historyMap[peerName] = msgList
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return historyMap
    }
}

object ImageUtils {
    fun compressUriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return null
            compressBitmapToBase64(originalBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun compressBitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val maxDimension = 1080
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                val targetWidth = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
                val targetHeight = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startChatService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                val peers by ChatService.discoveredPeers.collectAsState()
                val chatHistoryMap by ChatService.chatHistoryMap.collectAsState()
                val activeChatPeer by ChatService.activeChatPeer.collectAsState()
                val context = LocalContext.current

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            peers = peers,
                            onDeviceDoubleClick = { peer -> ChatService.setActiveChatPeer(peer) },
                            onManualRefreshClick = { refreshChatService() },
                            onExitClick = { exitAndTerminateApp() }
                        )

                        activeChatPeer?.let { peer ->
                            val peerMessages = chatHistoryMap[peer.name] ?: emptyList()

                            ChatFullScreenWindow(
                                peer = peer,
                                messages = peerMessages,
                                onDismiss = { ChatService.setActiveChatPeer(null) },
                                onSendMessage = { text -> ChatService.sendMessage(context, peer.endpointId, peer.name, text) },
                                onSendWhoAmI = { ChatService.sendWhoAmI(context, peer.endpointId, peer.name) },
                                onSendPhotoUri = { uri -> ChatService.sendPhotoUri(context, peer.endpointId, peer.name, uri) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startChatService() {
        val intent = Intent(this, ChatService::class.java).apply { action = ChatService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshChatService() {
        val intent = Intent(this, ChatService::class.java).apply { action = ChatService.ACTION_REFRESH }
        startService(intent)
    }

    private fun exitAndTerminateApp() {
        stopChatService()
        finishAndRemoveTask()
        exitProcess(0)
    }

    private fun stopChatService() {
        val intent = Intent(this, ChatService::class.java).apply { action = ChatService.ACTION_STOP }
        startService(intent)
    }
}

// ============================================================================
// COMPOSABLE UI COMPONENTS
// ============================================================================

@Composable
fun MainScreen(
    peers: List<PeerDevice>,
    onDeviceDoubleClick: (PeerDevice) -> Unit,
    onManualRefreshClick: () -> Unit,
    onExitClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OnlyChat", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onManualRefreshClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("🔄 Refresh", fontSize = 13.sp)
                }

                Button(
                    onClick = onExitClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("❌ Exit", fontSize = 13.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🟢 Auto-Refreshing active", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Scanning for nearby devices...", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers) { peer ->
                    DeviceCard(peer = peer, onDoubleClick = { onDeviceDoubleClick(peer) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    peer: PeerDevice,
    onDoubleClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onDoubleClick = onDoubleClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = if (peer.isOnlyChatActive) Color(0xFF2E7D32) else Color.Gray
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (peer.isOnlyChatActive) "OnlyChat Active" else "Disconnected",
                        fontSize = 12.sp,
                        color = if (peer.isOnlyChatActive) Color(0xFF2E7D32) else Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = peer.signalQuality,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ChatFullScreenWindow(
    peer: PeerDevice,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendWhoAmI: () -> Unit,
    onSendPhotoUri: (Uri) -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var fullScreenImageBase64 by remember { mutableStateOf<String?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val photoGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendPhotoUri(it) }
    }

    val fullHdCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempCameraUri?.let { uri ->
                onSendPhotoUri(uri)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Chat: ${peer.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Divider(modifier = Modifier.padding(vertical = 6.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollbar(listState, width = 4.dp, color = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Surface(
                                color = if (msg.isFromMe) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    if (msg.base64Image != null) {
                                        val bitmap = remember(msg.base64Image) {
                                            ImageUtils.decodeBase64ToBitmap(msg.base64Image)
                                        }
                                        bitmap?.let { b ->
                                            Image(
                                                bitmap = b.asImageBitmap(),
                                                contentDescription = "Shared photo",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(180.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        fullScreenImageBase64 = msg.base64Image
                                                    },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    if (msg.text.isNotBlank() && msg.base64Image == null) {
                                        Text(
                                            text = msg.text,
                                            color = if (msg.isFromMe) Color.White else Color.Black,
                                            fontSize = 15.sp
                                        )
                                    }

                                    // Display Progress Bar dynamically when transfer is in progress (< 1.0)
                                    if (msg.progress != null && msg.progress < 1f) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "Transferring...",
                                                    fontSize = 10.sp,
                                                    color = if (msg.isFromMe) Color.White.copy(alpha = 0.8f) else Color.DarkGray
                                                )
                                                Text(
                                                    text = "${(msg.progress * 100).toInt()}%",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (msg.isFromMe) Color.White else Color.Black
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            LinearProgressIndicator(
                                                progress = { msg.progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (msg.isFromMe) Color.White else MaterialTheme.colorScheme.primary,
                                                trackColor = Color.Gray.copy(alpha = 0.3f)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTimestamp(msg.timestamp),
                                            fontSize = 9.sp,
                                            color = if (msg.isFromMe) Color.White.copy(alpha = 0.7f) else Color.DarkGray
                                        )
                                        if (msg.isFromMe) {
                                            StatusSymbolIndicator(msg.status)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { photoGalleryLauncher.launch("image/*") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text("📷 Gallery", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val photoFile = File(context.cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    photoFile
                                )
                                tempCameraUri = uri
                                fullHdCameraLauncher.launch(uri)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text("🤳 Camera", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onSendWhoAmI,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text("👤 WhoAmI", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type message...") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        minLines = 2,
                        maxLines = 3
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    onSendMessage(textInput)
                                    textInput = ""
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Send", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("Close", fontSize = 12.sp)
                        }
                    }
                }
            }

            fullScreenImageBase64?.let { base64 ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable {
                            fullScreenImageBase64 = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = remember(base64) { ImageUtils.decodeBase64ToBitmap(base64) }
                    bitmap?.let { b ->
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = "Full Screen Photo Foreground",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    fullScreenImageBase64 = null
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusSymbolIndicator(status: MessageStatus) {
    val (symbol, color) = when (status) {
        MessageStatus.SENT -> Pair("✓ Sent", Color.White.copy(alpha = 0.6f))
        MessageStatus.DELIVERED -> Pair("✓✓ Received", Color.White.copy(alpha = 0.85f))
        MessageStatus.READ -> Pair("☑ Read", Color(0xFF80D8FF))
    }

    Text(
        text = symbol,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

fun Modifier.scrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = Color.Gray
): Modifier = this.drawWithContent {
    drawContent()

    val firstVisibleElementIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
    val totalItems = state.layoutInfo.totalItemsCount

    if (firstVisibleElementIndex != null && totalItems > 0) {
        val visibleItemsCount = state.layoutInfo.visibleItemsInfo.size
        val totalHeight = size.height

        val scrollbarHeight = (visibleItemsCount.toFloat() / totalItems.toFloat() * totalHeight)
            .coerceAtLeast(24.dp.toPx())
        val scrollbarTop = (firstVisibleElementIndex.toFloat() / totalItems.toFloat()) * totalHeight

        val scrollbarWidthPx = width.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - scrollbarWidthPx, scrollbarTop),
            size = Size(scrollbarWidthPx, scrollbarHeight),
            cornerRadius = CornerRadius(scrollbarWidthPx / 2, scrollbarWidthPx / 2),
            alpha = 0.6f
        )
    }
}

// ============================================================================
// SERVICE IMPLEMENTATION
// ============================================================================

class ChatService : Service() {

    companion object {
        const val SERVICE_ID = "com.example.onlychat.P2P_CHAT"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"

        private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
        val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers

        private val _chatHistoryMap = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
        val chatHistoryMap: StateFlow<Map<String, List<ChatMessage>>> = _chatHistoryMap

        private val _activeChatPeer = MutableStateFlow<PeerDevice?>(null)
        val activeChatPeer: StateFlow<PeerDevice?> = _activeChatPeer

        // Tracks Nearby Payload IDs to (PeerName, MessageID) for progress updates
        private val payloadMsgMap = mutableMapOf<Long, Pair<String, String>>()

        private var instance: ChatService? = null

        fun setActiveChatPeer(peer: PeerDevice?) {
            _activeChatPeer.value = peer
            if (peer != null) {
                instance?.sendReadReceiptsForPeer(peer)
            }
        }

        fun sendMessage(context: Context, endpointId: String, peerName: String, text: String) {
            val msgId = UUID.randomUUID().toString()
            val json = JSONObject().apply {
                put("type", "CHAT")
                put("id", msgId)
                put("text", text)
            }

            val payload = Payload.fromBytes(json.toString().toByteArray(StandardCharsets.UTF_8))
            payloadMsgMap[payload.id] = Pair(peerName, msgId)

            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)

            val newMessage = ChatMessage(id = msgId, text = text, isFromMe = true, status = MessageStatus.SENT, progress = 1f)
            val currentHistory = _chatHistoryMap.value.toMutableMap()
            val peerMessages = (currentHistory[peerName] ?: emptyList()) + newMessage
            currentHistory[peerName] = peerMessages
            _chatHistoryMap.value = currentHistory

            ChatStorageHelper.saveHistory(context, currentHistory)
        }

        fun sendWhoAmI(context: Context, endpointId: String, peerName: String) {
            val service = instance ?: return
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val androidVersion = Build.VERSION.RELEASE
            val sdkVersion = Build.VERSION.SDK_INT
            val hardware = Build.HARDWARE
            val board = Build.BOARD
            val locale = Locale.getDefault().toString()
            val processors = Runtime.getRuntime().availableProcessors()
            val totalMemoryMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)

            val detailedInfo = """
                👤 [Device Profile Info]
                • Manufacturer & Model: $manufacturer $model
                • Android Version: v$androidVersion (SDK $sdkVersion)
                • Hardware / Board: $hardware / $board
                • Locale / Language: $locale
                • CPU Cores: $processors
                • JVM Heap Memory: ${totalMemoryMb} MB
                • Session Identifier: ${service.getDetailedDeviceName()}
            """.trimIndent()

            sendMessage(context, endpointId, peerName, detailedInfo)
        }

        fun sendPhotoUri(context: Context, endpointId: String, peerName: String, uri: Uri) {
            val base64 = ImageUtils.compressUriToBase64(context, uri) ?: return
            instance?.sendPhotoMessage(context, endpointId, peerName, base64)
        }
    }

    private fun sendPhotoMessage(context: Context, endpointId: String, peerName: String, base64Image: String) {
        val msgId = UUID.randomUUID().toString()

        val json = JSONObject().apply {
            put("type", "PHOTO")
            put("id", msgId)
            put("image", base64Image)
        }

        val payload = Payload.fromBytes(json.toString().toByteArray(StandardCharsets.UTF_8))

        // Map payload ID to message ID to track sending progress
        payloadMsgMap[payload.id] = Pair(peerName, msgId)

        Nearby.getConnectionsClient(this).sendPayload(endpointId, payload)

        // Initialize progress at 0f so progress bar is active during send
        val newMessage = ChatMessage(
            id = msgId,
            text = "[Photo]",
            isFromMe = true,
            status = MessageStatus.SENT,
            base64Image = base64Image,
            progress = 0f
        )

        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = (currentHistory[peerName] ?: emptyList()) + newMessage
        currentHistory[peerName] = peerMessages
        _chatHistoryMap.value = currentHistory

        ChatStorageHelper.saveHistory(context, currentHistory)
    }

    private val connectingOrConnected = mutableSetOf<String>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val uniqueSessionSuffix = UUID.randomUUID().toString().take(4)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        val savedHistory = ChatStorageHelper.loadHistory(this)
        _chatHistoryMap.value = savedHistory
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startP2PDiscovery()
            }
            ACTION_REFRESH -> restartDiscovery()
            ACTION_STOP -> {
                stopP2PDiscovery()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun getDetailedDeviceName(): String {
        val model = Build.MODEL
        val baseName = if (model.length > 15) model.substring(0, 15) else model
        return "$baseName ($uniqueSessionSuffix)"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                "incoming_messages_channel",
                "OnlyChat Alerts & Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when nearby devices are discovered or incoming messages arrive"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "only_chat_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "OnlyChat Active", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OnlyChat Active")
            .setContentText("Listening for nearby devices and messages...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun notifyNewDeviceFound(endpointId: String, deviceName: String) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "incoming_messages_channel")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("New Device Discovered!")
            .setContentText("Found nearby device: $deviceName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(endpointId.hashCode(), notification)
    }

    private fun notifyUserAndBringToFront(senderName: String, messagePreview: String, peer: PeerDevice, msgId: String) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        try {
            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "incoming_messages_channel")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New Message from $senderName")
            .setContentText(messagePreview)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(msgId.hashCode(), notification)

        _activeChatPeer.value = peer
        sendAck(peer.endpointId, msgId, "ACK_DELIVERED")

        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startP2PDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        val myName = getDetailedDeviceName()

        val advOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        client.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, advOptions)
        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
    }

    private fun restartDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopDiscovery()
        client.stopAdvertising()
        startP2PDiscovery()
    }

    private fun addOrUpdatePeer(endpointId: String, name: String, isOnlyChatActive: Boolean = true) {
        val currentList = _discoveredPeers.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.endpointId == endpointId }

        val peer = PeerDevice(endpointId, name, isOnlyChatActive, signalQuality = "📶 Strong")

        if (existingIndex != -1) {
            currentList[existingIndex] = peer
        } else {
            currentList.add(peer)
            notifyNewDeviceFound(endpointId, name)
        }
        _discoveredPeers.value = currentList
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val isOnlyChat = info.serviceId == SERVICE_ID
            addOrUpdatePeer(endpointId, info.endpointName, isOnlyChatActive = isOnlyChat)

            val myName = getDetailedDeviceName()
            if (!connectingOrConnected.contains(endpointId) && myName >= info.endpointName) {
                connectingOrConnected.add(endpointId)
                Nearby.getConnectionsClient(this@ChatService)
                    .requestConnection(myName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { connectingOrConnected.remove(endpointId) }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (!connectingOrConnected.contains(endpointId)) {
                _discoveredPeers.value = _discoveredPeers.value.filter { it.endpointId != endpointId }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectingOrConnected.add(endpointId)
            addOrUpdatePeer(endpointId, info.endpointName, isOnlyChatActive = true)
            Nearby.getConnectionsClient(this@ChatService)
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { connectingOrConnected.remove(endpointId) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                connectingOrConnected.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectingOrConnected.remove(endpointId)
            _discoveredPeers.value = _discoveredPeers.value.filter { it.endpointId != endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val rawString = String(bytes, StandardCharsets.UTF_8)

            try {
                val json = JSONObject(rawString)
                val senderName = _discoveredPeers.value.find { it.endpointId == endpointId }?.name ?: "Nearby Peer"
                val peer = PeerDevice(endpointId, senderName, isOnlyChatActive = true)

                when (json.optString("type")) {
                    "CHAT" -> {
                        val msgId = json.getString("id")
                        val text = json.getString("text")

                        val newMessage = ChatMessage(id = msgId, text = text, isFromMe = false, progress = 1f)

                        val currentHistory = _chatHistoryMap.value.toMutableMap()
                        val peerMessages = (currentHistory[senderName] ?: emptyList()) + newMessage
                        currentHistory[senderName] = peerMessages
                        _chatHistoryMap.value = currentHistory

                        ChatStorageHelper.saveHistory(this@ChatService, currentHistory)

                        if (_activeChatPeer.value?.name == senderName) {
                            sendAck(endpointId, msgId, "ACK_READ")
                        }

                        notifyUserAndBringToFront(senderName, text, peer, msgId)
                    }

                    "PHOTO" -> {
                        val msgId = json.getString("id")
                        val base64Image = json.getString("image")

                        val newMessage = ChatMessage(id = msgId, text = "[Photo]", isFromMe = false, base64Image = base64Image, progress = 1f)

                        val currentHistory = _chatHistoryMap.value.toMutableMap()
                        val peerMessages = (currentHistory[senderName] ?: emptyList()) + newMessage
                        currentHistory[senderName] = peerMessages
                        _chatHistoryMap.value = currentHistory

                        ChatStorageHelper.saveHistory(this@ChatService, currentHistory)

                        if (_activeChatPeer.value?.name == senderName) {
                            sendAck(endpointId, msgId, "ACK_READ")
                        }

                        notifyUserAndBringToFront(senderName, "📷 Sent you a photo", peer, msgId)
                    }

                    "ACK_DELIVERED" -> {
                        val msgId = json.getString("id")
                        updateMessageStatus(senderName, msgId, MessageStatus.DELIVERED)
                    }

                    "ACK_READ" -> {
                        val msgId = json.getString("id")
                        updateMessageStatus(senderName, msgId, MessageStatus.READ)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Live progress update handler for ongoing transfers
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val totalBytes = update.totalBytes
            val payloadId = update.payloadId

            if (totalBytes > 0) {
                val calculatedProgress = update.bytesTransferred.toFloat() / totalBytes.toFloat()
                val mappedTarget = payloadMsgMap[payloadId]

                if (mappedTarget != null) {
                    val (peerName, msgId) = mappedTarget
                    updateMessageProgress(peerName, msgId, calculatedProgress)

                    // Transfer complete or failed -> clean up mapping
                    if (update.status == PayloadTransferUpdate.Status.SUCCESS ||
                        update.status == PayloadTransferUpdate.Status.FAILURE ||
                        update.status == PayloadTransferUpdate.Status.CANCELED) {
                        payloadMsgMap.remove(payloadId)
                    }
                }
            }
        }
    }

    private fun updateMessageProgress(peerName: String, msgId: String, progressRatio: Float) {
        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = currentHistory[peerName]?.toMutableList() ?: return

        val index = peerMessages.indexOfFirst { it.id == msgId }
        if (index != -1) {
            val existing = peerMessages[index]
            peerMessages[index] = existing.copy(progress = progressRatio.coerceIn(0f, 1f))
            currentHistory[peerName] = peerMessages
            _chatHistoryMap.value = currentHistory
        }
    }

    private fun sendAck(endpointId: String, msgId: String, type: String) {
        val ackJson = JSONObject().apply {
            put("type", type)
            put("id", msgId)
        }
        val payload = Payload.fromBytes(ackJson.toString().toByteArray(StandardCharsets.UTF_8))
        Nearby.getConnectionsClient(this).sendPayload(endpointId, payload)
    }

    private fun sendReadReceiptsForPeer(peer: PeerDevice) {
        val messages = _chatHistoryMap.value[peer.name] ?: return
        messages.filter { !it.isFromMe }.forEach { msg ->
            sendAck(peer.endpointId, msg.id, "ACK_READ")
        }
    }

    private fun updateMessageStatus(peerName: String, msgId: String, newStatus: MessageStatus) {
        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = currentHistory[peerName]?.toMutableList() ?: return

        val index = peerMessages.indexOfFirst { it.id == msgId }
        if (index != -1) {
            val existing = peerMessages[index]
            if (newStatus.ordinal > existing.status.ordinal) {
                peerMessages[index] = existing.copy(status = newStatus)
                currentHistory[peerName] = peerMessages
                _chatHistoryMap.value = currentHistory
                ChatStorageHelper.saveHistory(this, currentHistory)
            }
        }
    }

    private fun stopP2PDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectingOrConnected.clear()
        payloadMsgMap.clear()
        _discoveredPeers.value = emptyList()
        _activeChatPeer.value = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopP2PDiscovery()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}