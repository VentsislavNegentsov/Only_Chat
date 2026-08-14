package com.example.onlychat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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

        // Turn screen on and unlock if device receives incoming chat while locked
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
                            onExitClick = {
                                stopChatService()
                                finish()
                            }
                        )

                        activeChatPeer?.let { peer ->
                            val peerMessages = chatHistoryMap[peer.endpointId] ?: emptyList()

                            ChatPopupDialog(
                                peer = peer,
                                messages = peerMessages,
                                onDismiss = { ChatService.setActiveChatPeer(null) },
                                onSendMessage = { text -> ChatService.sendMessage(peer.endpointId, text) },
                                onSendWhoAmI = { ChatService.sendWhoAmI(peer.endpointId) },
                                onSendPhotoUri = { uri -> ChatService.sendPhotoUri(context, peer.endpointId, uri) },
                                onSendPhotoBitmap = { bitmap -> ChatService.sendPhotoBitmap(peer.endpointId, bitmap) }
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

    private fun stopChatService() {
        val intent = Intent(this, ChatService::class.java).apply { action = ChatService.ACTION_STOP }
        startService(intent)
    }
}

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
            Text("🟢 Auto-Refreshing every 5s", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Scanning for nearby devices...\nAuto-refresh is active.", color = Color.Gray)
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
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                text = peer.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChatPopupDialog(
    peer: PeerDevice,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendWhoAmI: () -> Unit,
    onSendPhotoUri: (Uri) -> Unit,
    onSendPhotoBitmap: (Bitmap) -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val photoGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendPhotoUri(it) }
    }

    val frontCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            bitmap?.let { b -> onSendPhotoBitmap(b) }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat: ${peer.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .scrollbar(listState, width = 4.dp, color = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(messages) { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Surface(
                                color = if (msg.isFromMe) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    if (msg.base64Image != null) {
                                        val bitmap = remember(msg.base64Image) {
                                            ImageUtils.decodeBase64ToBitmap(msg.base64Image)
                                        }
                                        bitmap?.let { b ->
                                            Image(
                                                bitmap = b.asImageBitmap(),
                                                contentDescription = "Shared photo",
                                                modifier = Modifier
                                                    .widthIn(max = 200.dp)
                                                    .heightIn(max = 200.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    if (msg.text.isNotBlank() && msg.base64Image == null) {
                                        Text(
                                            text = msg.text,
                                            color = if (msg.isFromMe) Color.White else Color.Black,
                                            fontSize = 14.sp
                                        )
                                    }
                                    if (msg.isFromMe) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        StatusSymbolIndicator(msg.status)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = { photoGalleryLauncher.launch("image/*") },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("📷 Gallery", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra("android.intent.extras.CAMERA_FACING", 1)
                                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                                putExtra("usefrontcamera", true)
                            }
                            frontCameraLauncher.launch(intent)
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("🤳 Selfie", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onSendWhoAmI,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("👤 Who Am I", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type message...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (textInput.isNotBlank()) {
                    onSendMessage(textInput)
                    textInput = ""
                }
            }) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
        color = color,
        modifier = Modifier.padding(top = 2.dp)
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