package com.example.onlychat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startChatService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                val peers by ChatService.discoveredPeers.collectAsState()
                val messages by ChatService.chatMessages.collectAsState()
                val incomingPopup by ChatService.incomingPopup.collectAsState()

                var selectedPeer by remember { mutableStateOf<PeerDevice?>(null) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Main Device List View
                        DeviceListScreen(
                            peers = peers,
                            onDeviceDoubleClick = { peer ->
                                selectedPeer = peer
                            }
                        )

                        // Active Chat Screen / Sheet
                        selectedPeer?.let { peer ->
                            ChatDialog(
                                peer = peer,
                                messages = messages.filter { it.senderId == peer.endpointId },
                                onDismiss = { selectedPeer = null },
                                onSendMessage = { text ->
                                    ChatService.sendMessage(peer.endpointId, text, peer.name)
                                }
                            )
                        }

                        // Instant Pop-up Dialog when receiving a message
                        incomingPopup?.let { msg ->
                            AlertDialog(
                                onDismissRequest = { ChatService.clearPopup() },
                                title = { Text("📬 Message from ${msg.senderName}") },
                                text = { Text(msg.text, fontSize = 16.sp) },
                                confirmButton = {
                                    Button(onClick = {
                                        // Open chat window directly with sender
                                        selectedPeer = PeerDevice(msg.senderId, msg.senderName)
                                        ChatService.clearPopup()
                                    }) {
                                        Text("Open Chat")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { ChatService.clearPopup() }) {
                                        Text("Dismiss")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
        val intent = Intent(this, ChatService::class.java).apply {
            action = ChatService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun DeviceListScreen(
    peers: List<PeerDevice>,
    onDeviceDoubleClick: (PeerDevice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("OnlyChat Nearby", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Double-click a device to send a message",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Divider()
        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Scanning for nearby devices in range...", color = Color.Gray)
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
            .combinedClickable(
                onClick = {}, // Normal single click does nothing
                onDoubleClick = onDoubleClick // DOUBLE CLICK triggers chat!
            )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = peer.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Double Tap",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ChatDialog(
    peer: PeerDevice,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat with ${peer.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = false
                ) {
                    items(messages) { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            contentAlignment = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Surface(
                                color = if (msg.isFromMe) MaterialTheme.colorScheme.primary else Color.LightGray,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = if (msg.isFromMe) Color.White else Color.Black,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type message...") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput)
                        textInput = ""
                    }
                }
            ) {
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