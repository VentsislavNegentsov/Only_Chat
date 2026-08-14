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
data class ChatMessage(val text: String, val isFromMe: Boolean, val timestamp: Long = System.currentTimeMillis())

class ChatService : Service() {

    companion object {
        const val SERVICE_ID = "com.example.onlychat.P2P_CHAT"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_REFRESH = "ACTION_REFRESH"

        // Discovered Devices List
        private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
        val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers

        // Chat History map keyed by endpointId (Persists messages per client!)
        private val _chatHistoryMap = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
        val chatHistoryMap: StateFlow<Map<String, List<ChatMessage>>> = _chatHistoryMap

        // Currently Active Pop-up Peer (Only 1 popup at a time)
        private val _activeChatPeer = MutableStateFlow<PeerDevice?>(null)
        val activeChatPeer: StateFlow<PeerDevice?> = _activeChatPeer

        fun setActiveChatPeer(peer: PeerDevice?) {
            _activeChatPeer.value = peer
        }

        private var instance: ChatService? = null

        fun sendMessage(endpointId: String, text: String) {
            instance?.let { service ->
                val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))
                Nearby.getConnectionsClient(service).sendPayload(endpointId, payload)

                // Save message into persistent history for this client
                val newMessage = ChatMessage(text = text, isFromMe = true)
                val currentHistory = _chatHistoryMap.value.toMutableMap()
                val peerMessages = (currentHistory[endpointId] ?: emptyList()) + newMessage
                currentHistory[endpointId] = peerMessages
                _chatHistoryMap.value = currentHistory
            }
        }
    }

    private val connectingOrConnected = mutableSetOf<String>()

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
            ACTION_REFRESH -> {
                restartDiscovery()
            }
            ACTION_STOP -> {
                stopP2PDiscovery()
                stopForeground(true)
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

    private fun restartDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopDiscovery()
        client.stopAdvertising()
        connectingOrConnected.clear()
        _discoveredPeers.value = emptyList()

        // Brief delay before restarting discovery
        startP2PDiscovery()
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

            // RACE CONDITION FIX: Only request if not already connecting or connected
            if (!connectingOrConnected.contains(endpointId)) {
                connectingOrConnected.add(endpointId)
                Nearby.getConnectionsClient(this@ChatService)
                    .requestConnection(Build.MODEL ?: "Android Device", endpointId, connectionLifecycleCallback)
                    .addOnFailureListener {
                        connectingOrConnected.remove(endpointId)
                    }
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
            addOrUpdatePeer(endpointId, info.endpointName)

            // Auto-accept connection on both sides
            Nearby.getConnectionsClient(this@ChatService)
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    connectingOrConnected.remove(endpointId)
                }
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
            val messageText = String(bytes, StandardCharsets.UTF_8)
            val senderName = _discoveredPeers.value.find { it.endpointId == endpointId }?.name ?: "Nearby Peer"
            val peer = PeerDevice(endpointId, senderName)

            // 1. Add incoming message to persistent client history
            val newMessage = ChatMessage(text = messageText, isFromMe = false)
            val currentHistory = _chatHistoryMap.value.toMutableMap()
            val peerMessages = (currentHistory[endpointId] ?: emptyList()) + newMessage
            currentHistory[endpointId] = peerMessages
            _chatHistoryMap.value = currentHistory

            // 2. Open pop-up window for this client (Only 1 active popup allowed)
            _activeChatPeer.value = peer
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun stopP2PDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectingOrConnected.clear()
        _discoveredPeers.value = emptyList()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopP2PDiscovery()
        instance = null
        super.onDestroy()
    }
}