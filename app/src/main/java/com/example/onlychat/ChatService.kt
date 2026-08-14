package com.example.onlychat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets

data class PeerDevice(val endpointId: String, val name: String)
data class ChatMessage(val senderId: String, val senderName: String, val text: String, val isFromMe: Boolean)

class ChatService : Service() {

    companion object {
        const val SERVICE_ID = "com.example.onlychat.P2P_CHAT"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
        val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers

        private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

        private val _incomingPopup = MutableStateFlow<ChatMessage?>(null)
        val incomingPopup: StateFlow<ChatMessage?> = _incomingPopup

        // Active connections set
        private val connectedEndpoints = mutableSetOf<String>()

        fun clearPopup() {
            _incomingPopup.value = null
        }

        private var instance: ChatService? = null

        fun sendMessage(endpointId: String, text: String, deviceName: String) {
            instance?.let { service ->
                val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))

                // Send P2P payload over Nearby Connections
                Nearby.getConnectionsClient(service).sendPayload(endpointId, payload)

                // Save to local message history
                _chatMessages.value = _chatMessages.value + ChatMessage(endpointId, deviceName, text, isFromMe = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startP2PDiscovery()
            }
            ACTION_STOP -> {
                stopP2PDiscovery()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "only_chat_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "OnlyChat Local Discovery",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OnlyChat Active")
            .setContentText("Scanning for nearby devices...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startP2PDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        val myName = Build.MODEL ?: "Android Device"

        val advOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        client.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, advOptions)
        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
    }

    private fun addOrUpdatePeer(endpointId: String, name: String) {
        val currentList = _discoveredPeers.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.endpointId == endpointId }

        if (existingIndex != -1) {
            currentList[existingIndex] = PeerDevice(endpointId, name)
        } else {
            currentList.add(PeerDevice(endpointId, name))
        }
        _discoveredPeers.value = currentList
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            addOrUpdatePeer(endpointId, info.endpointName)

            // Discoverer requests connection to the advertiser
            Nearby.getConnectionsClient(this@ChatService)
                .requestConnection(Build.MODEL ?: "Android Device", endpointId, connectionLifecycleCallback)
                .addOnFailureListener { /* Ignore if connection request is already pending */ }
        }

        override fun onEndpointLost(endpointId: String) {
            if (!connectedEndpoints.contains(endpointId)) {
                _discoveredPeers.value = _discoveredPeers.value.filter { it.endpointId != endpointId }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // FIX: Add peer on BOTH receiving and initiating sides instantly!
            addOrUpdatePeer(endpointId, info.endpointName)

            // Auto-accept two-way connection
            Nearby.getConnectionsClient(this@ChatService)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            _discoveredPeers.value = _discoveredPeers.value.filter { it.endpointId != endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val messageText = String(bytes, StandardCharsets.UTF_8)
            val senderName = _discoveredPeers.value.find { it.endpointId == endpointId }?.name ?: "Nearby Peer"

            val incomingMessage = ChatMessage(endpointId, senderName, messageText, isFromMe = false)

            _chatMessages.value = _chatMessages.value + incomingMessage
            _incomingPopup.value = incomingMessage
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun stopP2PDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        _discoveredPeers.value = emptyList()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopP2PDiscovery()
        instance = null
        super.onDestroy()
    }
}