package com.example.onlychat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

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
    val imagePath: String? = null,
    val progress: Float? = null,
    val payloadId: Long? = null
)

// ============================================================================
// CUSTOM CONTRACT FOR FRONT/SELFIE CAMERA
// ============================================================================

class TakePictureWithFrontCamera : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return super.createIntent(context, input).apply {
            putExtra("android.intent.extras.CAMERA_FACING", 1)
            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
    }
}

// ============================================================================
// UTILITY OBJECTS & STORAGE HELPER
// ============================================================================

object ChatStorageHelper {
    private const val FILE_NAME = "chat_history_v3.json"

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
                        put("imagePath", msg.imagePath ?: "")
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
                    val imgPath = obj.optString("imagePath", "")
                    val msg = ChatMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        text = obj.optString("text", ""),
                        isFromMe = obj.optBoolean("isFromMe", true),
                        status = try { MessageStatus.valueOf(obj.optString("status", "SENT")) } catch (e: Exception) { MessageStatus.SENT },
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        imagePath = if (imgPath.isNotEmpty()) imgPath else null,
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
    fun copyUriToCacheFile(context: Context, uri: Uri): File? {
        return try {
            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (file.exists()) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(filePath)
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
            requestBatteryOptimizationExemption()
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
                            chatHistoryMap = chatHistoryMap,
                            onDeviceClick = { peer -> ChatService.setActiveChatPeer(peer) },
                            onHuntClick = { moveTaskToBack(true) }
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

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
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
}

// ============================================================================
// COMPOSABLE UI COMPONENTS
// ============================================================================

@Composable
fun MainScreen(
    peers: List<PeerDevice>,
    chatHistoryMap: Map<String, List<ChatMessage>>,
    onDeviceClick: (PeerDevice) -> Unit,
    onHuntClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OnlyChat", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            OutlinedButton(
                onClick = onHuntClick,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("🏹 Hunt", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🟢 Searching for nearby devices", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Searching for nearby devices", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers) { peer ->
                    val hasHistory = chatHistoryMap[peer.name]?.isNotEmpty() == true
                    DeviceCard(peer = peer, hasHistory = hasHistory, onClick = { onDeviceClick(peer) })
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    peer: PeerDevice,
    hasHistory: Boolean,
    onClick: () -> Unit
) {
    val nameParts = remember(peer.name) { peer.name.split("|") }
    val mainName = nameParts.firstOrNull() ?: peer.name
    val deviceInfo = nameParts.getOrNull(1) ?: ""

    val cardBgColor = if (hasHistory) Color(0xFFE2F3E5) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (hasHistory) Color(0xFF1B4D2E) else MaterialTheme.colorScheme.onSurfaceVariant
    val subTextColor = if (hasHistory) Color(0xFF2E6B40) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mainName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                if (deviceInfo.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = deviceInfo,
                        fontSize = 10.sp,
                        color = subTextColor,
                        fontWeight = FontWeight.Medium
                    )
                }

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
                color = if (hasHistory) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = peer.signalQuality,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasHistory) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onPrimaryContainer,
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
    var showEmojiPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var fullScreenImagePath by remember { mutableStateOf<String?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cleanPeerName = remember(peer.name) { peer.name.split("|").firstOrNull() ?: peer.name }
    val commonEmojis = remember { listOf("😊", "😂", "❤️", "👍", "🔥", "🎉", "🙏", "😎", "😍", "🥳") }

    val photoGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendPhotoUri(it) }
    }

    val frontCameraLauncher = rememberLauncherForActivityResult(
        contract = TakePictureWithFrontCamera()
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
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Text("←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chat: $cleanPeerName", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollbar(listState, width = 4.dp, color = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Bottom)
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
                                    if (msg.imagePath != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        ) {
                                            val bitmap = remember(msg.imagePath) {
                                                ImageUtils.loadBitmapFromFile(msg.imagePath)
                                            }
                                            bitmap?.let { b ->
                                                Image(
                                                    bitmap = b.asImageBitmap(),
                                                    contentDescription = "Shared photo",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clickable {
                                                            if (msg.progress == null || msg.progress >= 1f) {
                                                                fullScreenImagePath = msg.imagePath
                                                            }
                                                        },
                                                    contentScale = ContentScale.Crop
                                                )
                                            }

                                            if (msg.progress != null && msg.progress < 1f) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.65f))
                                                        .padding(12.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = "Transferring Photo (${(msg.progress * 100).toInt()}%)",
                                                            color = Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        LinearProgressIndicator(
                                                            progress = { msg.progress },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(6.dp)
                                                                .clip(RoundedCornerShape(3.dp)),
                                                            color = Color(0xFF80D8FF),
                                                            trackColor = Color.White.copy(alpha = 0.3f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (msg.text.isNotBlank() && msg.imagePath == null) {
                                        Text(
                                            text = msg.text,
                                            color = if (msg.isFromMe) Color.White else Color.Black,
                                            fontSize = 15.sp
                                        )
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

                Spacer(modifier = Modifier.height(4.dp))

                if (showEmojiPicker) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            commonEmojis.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 22.sp,
                                    modifier = Modifier
                                        .clickable { textInput += emoji }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = { photoGalleryLauncher.launch("image/*") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
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
                                frontCameraLauncher.launch(uri)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text("🤳 Selfie", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onSendWhoAmI,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text("👤 WhoAmI", fontSize = 11.sp)
                    }

                    IconButton(
                        onClick = { showEmojiPicker = !showEmojiPicker },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("😊", fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type message...", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        minLines = 1,
                        maxLines = 3
                    )

                    Button(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput)
                                textInput = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("Send", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
            }

            fullScreenImagePath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable { fullScreenImagePath = null },
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = remember(path) { ImageUtils.loadBitmapFromFile(path) }
                    bitmap?.let { b ->
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = "Full Screen Photo",
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { fullScreenImagePath = null },
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
// SERVICE IMPLEMENTATION WITH WATCHDOG, RECOVERABILITY & PING HEARTBEAT
// ============================================================================

class ChatService : Service() {

    companion object {
        const val SERVICE_ID = "com.example.onlychat.P2P_CHAT"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"

        private val MY_SESSION_ID = UUID.randomUUID().toString().take(6)

        private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
        val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers

        private val _chatHistoryMap = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
        val chatHistoryMap: StateFlow<Map<String, List<ChatMessage>>> = _chatHistoryMap

        private val _activeChatPeer = MutableStateFlow<PeerDevice?>(null)
        val activeChatPeer: StateFlow<PeerDevice?> = _activeChatPeer

        private val payloadMsgMap = mutableMapOf<Long, Pair<String, String>>()
        private val incomingPhotoMeta = mutableMapOf<Long, Pair<String, String>>()

        private val peerLastSeenMap = mutableMapOf<String, Long>()
        private val peerFirstSeenMap = mutableMapOf<String, Long>()
        private val candidatePeersMap = mutableMapOf<String, PeerDevice>()
        private val notifiedPeersSet = mutableSetOf<String>()

        private var instance: ChatService? = null

        fun isSelf(endpointName: String): Boolean {
            if (endpointName.contains("#$MY_SESSION_ID")) return true

            val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val model = Build.MODEL
            val myDeviceSignature = "$manufacturer $model"

            if (endpointName.startsWith(myDeviceSignature) && endpointName.contains(MY_SESSION_ID)) {
                return true
            }

            return false
        }

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

            val detailedInfo = """
                👤 [Device Profile Info]
                • Model: $manufacturer $model
                • OS: Android $androidVersion (SDK $sdkVersion)
                • Board/Hardware: $hardware / $board
                • Locale: $locale
                • CPU Cores: $processors
                • Session ID: ${service.getDetailedDeviceName()}
            """.trimIndent()

            sendMessage(context, endpointId, peerName, detailedInfo)
        }

        fun sendPhotoUri(context: Context, endpointId: String, peerName: String, uri: Uri) {
            val file = ImageUtils.copyUriToCacheFile(context, uri) ?: return
            instance?.sendPhotoFile(context, endpointId, peerName, file)
        }
    }

    private fun sendPhotoFile(context: Context, endpointId: String, peerName: String, file: File) {
        val msgId = UUID.randomUUID().toString()
        val filePayload = Payload.fromFile(file)

        val metaJson = JSONObject().apply {
            put("type", "PHOTO_META")
            put("id", msgId)
            put("payloadId", filePayload.id)
        }

        val metaPayload = Payload.fromBytes(metaJson.toString().toByteArray(StandardCharsets.UTF_8))
        payloadMsgMap[filePayload.id] = Pair(peerName, msgId)

        val client = Nearby.getConnectionsClient(this)
        client.sendPayload(endpointId, metaPayload)
        client.sendPayload(endpointId, filePayload)

        val newMessage = ChatMessage(
            id = msgId,
            text = "[Photo]",
            isFromMe = true,
            status = MessageStatus.SENT,
            imagePath = file.absolutePath,
            progress = 0f,
            payloadId = filePayload.id
        )

        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = (currentHistory[peerName] ?: emptyList()) + newMessage
        currentHistory[peerName] = peerMessages
        _chatHistoryMap.value = currentHistory

        ChatStorageHelper.saveHistory(context, currentHistory)
    }

    private val connectingOrConnected = mutableSetOf<String>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                val isAirplaneOn = intent.getBooleanExtra("state", false)
                if (!isAirplaneOn) {
                    restartDiscovery()
                } else {
                    stopP2PDiscovery()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OnlyChat::P2PWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        createNotificationChannels()

        val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        registerReceiver(airplaneModeReceiver, filter)

        val savedHistory = ChatStorageHelper.loadHistory(this)
        _chatHistoryMap.value = savedHistory
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startP2PDiscovery()
                startPingLoop()
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

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = serviceScope.launch {
            while (isActive) {
                delay(2_000)
                val now = System.currentTimeMillis()
                val candidates = candidatePeersMap.keys.toList()

                for (endpointId in candidates) {
                    val lastSeen = peerLastSeenMap[endpointId] ?: now

                    if (now - lastSeen > 12_000) {
                        peerFirstSeenMap.remove(endpointId)
                        peerLastSeenMap.remove(endpointId)
                        candidatePeersMap.remove(endpointId)
                        connectingOrConnected.remove(endpointId)
                        notifiedPeersSet.remove(endpointId)
                        Nearby.getConnectionsClient(this@ChatService).disconnectFromEndpoint(endpointId)
                        continue
                    }

                    if (connectingOrConnected.contains(endpointId)) {
                        val pingJson = JSONObject().apply { put("type", "PING") }
                        val payload = Payload.fromBytes(pingJson.toString().toByteArray(StandardCharsets.UTF_8))
                        Nearby.getConnectionsClient(this@ChatService).sendPayload(endpointId, payload)
                    }
                }

                updateVisiblePeersList()
            }
        }
    }

    private fun updateVisiblePeersList() {
        val now = System.currentTimeMillis()

        val stablePeers = candidatePeersMap.values.filter { peer ->
            val firstSeen = peerFirstSeenMap[peer.endpointId] ?: now
            val lastSeen = peerLastSeenMap[peer.endpointId] ?: 0L
            (now - firstSeen >= 10_000) && (now - lastSeen <= 12_000)
        }

        for (peer in stablePeers) {
            if (!notifiedPeersSet.contains(peer.endpointId)) {
                notifiedPeersSet.add(peer.endpointId)
                notifyNewDeviceFound(peer.endpointId, peer.name)
            }
        }

        _discoveredPeers.value = stablePeers
    }

    fun getDetailedDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val model = Build.MODEL
        val hardware = Build.HARDWARE
        val board = Build.BOARD
        return "$manufacturer $model|$hardware • $board|#$MY_SESSION_ID"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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

        val cleanName = deviceName.split("|").firstOrNull() ?: deviceName

        val notification = NotificationCompat.Builder(this, "incoming_messages_channel")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("New Device Discovered!")
            .setContentText("Found nearby device: $cleanName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanSenderName = senderName.split("|").firstOrNull() ?: senderName

        val notification = NotificationCompat.Builder(this, "incoming_messages_channel")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New Message from $cleanSenderName")
            .setContentText(messagePreview)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        if (isSelf(name)) return

        val now = System.currentTimeMillis()
        if (!peerFirstSeenMap.containsKey(endpointId)) {
            peerFirstSeenMap[endpointId] = now
        }
        peerLastSeenMap[endpointId] = now

        val peer = PeerDevice(endpointId, name, isOnlyChatActive, signalQuality = "📶 Strong")
        candidatePeersMap[endpointId] = peer

        updateVisiblePeersList()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val myName = getDetailedDeviceName()

            if (isSelf(info.endpointName) || info.endpointName == myName) return

            val isOnlyChat = info.serviceId == SERVICE_ID
            addOrUpdatePeer(endpointId, info.endpointName, isOnlyChatActive = isOnlyChat)

            if (!connectingOrConnected.contains(endpointId) && myName >= info.endpointName) {
                connectingOrConnected.add(endpointId)
                Nearby.getConnectionsClient(this@ChatService)
                    .requestConnection(myName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { connectingOrConnected.remove(endpointId) }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            peerFirstSeenMap.remove(endpointId)
            peerLastSeenMap.remove(endpointId)
            candidatePeersMap.remove(endpointId)
            connectingOrConnected.remove(endpointId)
            notifiedPeersSet.remove(endpointId)
            updateVisiblePeersList()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val myName = getDetailedDeviceName()

            if (isSelf(info.endpointName) || info.endpointName == myName) return

            connectingOrConnected.add(endpointId)
            addOrUpdatePeer(endpointId, info.endpointName, isOnlyChatActive = true)
            Nearby.getConnectionsClient(this@ChatService)
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { connectingOrConnected.remove(endpointId) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                peerFirstSeenMap.remove(endpointId)
                peerLastSeenMap.remove(endpointId)
                candidatePeersMap.remove(endpointId)
                connectingOrConnected.remove(endpointId)
                notifiedPeersSet.remove(endpointId)
                updateVisiblePeersList()
            }
        }

        override fun onDisconnected(endpointId: String) {
            peerFirstSeenMap.remove(endpointId)
            peerLastSeenMap.remove(endpointId)
            candidatePeersMap.remove(endpointId)
            connectingOrConnected.remove(endpointId)
            notifiedPeersSet.remove(endpointId)
            updateVisiblePeersList()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            peerLastSeenMap[endpointId] = System.currentTimeMillis()
            val senderName = candidatePeersMap[endpointId]?.name ?: "Nearby Peer"
            val peer = PeerDevice(endpointId, senderName, isOnlyChatActive = true)

            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val rawString = String(bytes, StandardCharsets.UTF_8)

                try {
                    val json = JSONObject(rawString)

                    when (json.optString("type")) {
                        "PING" -> {
                            val pongJson = JSONObject().apply { put("type", "PONG") }
                            val pongPayload = Payload.fromBytes(pongJson.toString().toByteArray(StandardCharsets.UTF_8))
                            Nearby.getConnectionsClient(this@ChatService).sendPayload(endpointId, pongPayload)
                        }

                        "PONG" -> {
                            // Timestamp updated by peerLastSeenMap above
                        }

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

                        "PHOTO_META" -> {
                            val msgId = json.getString("id")
                            val filePayloadId = json.getLong("payloadId")
                            incomingPhotoMeta[filePayloadId] = Pair(senderName, msgId)
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
            } else if (payload.type == Payload.Type.FILE) {
                val meta = incomingPhotoMeta[payload.id] ?: return
                val (peerName, msgId) = meta

                val targetFile = File(cacheDir, "recv_${System.currentTimeMillis()}.jpg")
                val pfd = payload.asFile()?.asParcelFileDescriptor()
                if (pfd != null) {
                    try {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val newMessage = ChatMessage(
                    id = msgId,
                    text = "[Photo]",
                    isFromMe = false,
                    imagePath = targetFile.absolutePath,
                    progress = 1f
                )

                val currentHistory = _chatHistoryMap.value.toMutableMap()
                val peerMessages = (currentHistory[peerName] ?: emptyList()) + newMessage
                currentHistory[peerName] = peerMessages
                _chatHistoryMap.value = currentHistory

                ChatStorageHelper.saveHistory(this@ChatService, currentHistory)
                incomingPhotoMeta.remove(payload.id)

                if (_activeChatPeer.value?.name == peerName) {
                    sendAck(endpointId, msgId, "ACK_READ")
                }

                notifyUserAndBringToFront(peerName, "📷 Sent you a photo", peer, msgId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            peerLastSeenMap[endpointId] = System.currentTimeMillis()
            val totalBytes = update.totalBytes
            val payloadId = update.payloadId

            if (totalBytes > 0) {
                val calculatedProgress = update.bytesTransferred.toFloat() / totalBytes.toFloat()
                val mappedTarget = payloadMsgMap[payloadId]

                if (mappedTarget != null) {
                    val (peerName, msgId) = mappedTarget
                    updateMessageProgress(peerName, msgId, calculatedProgress)

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
        incomingPhotoMeta.clear()
        peerLastSeenMap.clear()
        peerFirstSeenMap.clear()
        candidatePeersMap.clear()
        notifiedPeersSet.clear()
        pingJob?.cancel()
        _discoveredPeers.value = emptyList()
        _activeChatPeer.value = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        try {
            unregisterReceiver(airplaneModeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopP2PDiscovery()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}