package com.example.onlychat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class MessageStatus { SENT, DELIVERED, READ }

data class PeerDevice(val endpointId: String, val name: String)
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromMe: Boolean,
    var status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis()
)

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

        private var instance: ChatService? = null

        fun setActiveChatPeer(peer: PeerDevice?) {
            _activeChatPeer.value = peer
            if (peer != null) {
                instance?.sendReadReceiptsForPeer(peer.endpointId)
            }
        }

        fun sendMessage(endpointId: String, text: String) {
            instance?.let { service ->
                val msgId = UUID.randomUUID().toString()

                val json = JSONObject().apply {
                    put("type", "CHAT")
                    put("id", msgId)
                    put("text", text)
                }

                val payload = Payload.fromBytes(json.toString().toByteArray(StandardCharsets.UTF_8))
                Nearby.getConnectionsClient(service).sendPayload(endpointId, payload)

                val newMessage = ChatMessage(id = msgId, text = text, isFromMe = true, status = MessageStatus.SENT)
                val currentHistory = _chatHistoryMap.value.toMutableMap()
                val peerMessages = (currentHistory[endpointId] ?: emptyList()) + newMessage
                currentHistory[endpointId] = peerMessages
                _chatHistoryMap.value = currentHistory
            }
        }
    }

    private val connectingOrConnected = mutableSetOf<String>()

    // Coroutine Scope for 5-Second Auto-Refresh Timer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoRefreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startP2PDiscovery()
                startAutoRefreshLoop()
            }
            ACTION_REFRESH -> restartDiscovery()
            ACTION_STOP -> {
                stopAutoRefreshLoop()
                stopP2PDiscovery()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(5000L) // Wait 5 seconds
                restartDiscovery()
            }
        }
    }

    private fun stopAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun getDetailedDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val deviceCode = Build.DEVICE
        val androidVer = Build.VERSION.RELEASE

        val customName = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) { null }

        val fullModel = if (model.lowercase().startsWith(manufacturer.lowercase())) model else "$manufacturer $model"

        return if (!customName.isNullOrBlank() && customName != model) {
            "$customName ($fullModel - $deviceCode / Android $androidVer)"
        } else {
            "$fullModel ($deviceCode / Android $androidVer)"
        }
    }

    private fun startForegroundNotification() {
        val channelId = "only_chat_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "OnlyChat Discovery", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OnlyChat Active")
            .setContentText("Auto-refreshing nearby devices every 5s...")
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
        val myDetailedName = getDetailedDeviceName()

        val advOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        client.startAdvertising(myDetailedName, SERVICE_ID, connectionLifecycleCallback, advOptions)
        client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
    }

    private fun restartDiscovery() {
        val client = Nearby.getConnectionsClient(this)
        client.stopDiscovery()
        client.stopAdvertising()

        // Retain active connected endpoints so existing chats aren't severed
        _discoveredPeers.value = _discoveredPeers.value.filter { connectingOrConnected.contains(it.endpointId) }

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
            if (!connectingOrConnected.contains(endpointId)) {
                connectingOrConnected.add(endpointId)
                Nearby.getConnectionsClient(this@ChatService)
                    .requestConnection(getDetailedDeviceName(), endpointId, connectionLifecycleCallback)
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
            addOrUpdatePeer(endpointId, info.endpointName)
            Nearby.getConnectionsClient(this@ChatService)
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { connectingOrConnected.remove(endpointId) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) connectingOrConnected.remove(endpointId)
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
                when (json.optString("type")) {
                    "CHAT" -> {
                        val msgId = json.getString("id")
                        val text = json.getString("text")
                        val senderName = _discoveredPeers.value.find { it.endpointId == endpointId }?.name ?: "Nearby Peer"

                        val newMessage = ChatMessage(id = msgId, text = text, isFromMe = false)
                        val currentHistory = _chatHistoryMap.value.toMutableMap()
                        val peerMessages = (currentHistory[endpointId] ?: emptyList()) + newMessage
                        currentHistory[endpointId] = peerMessages
                        _chatHistoryMap.value = currentHistory

                        val peer = PeerDevice(endpointId, senderName)
                        _activeChatPeer.value = peer

                        sendAck(endpointId, msgId, "ACK_DELIVERED")

                        if (_activeChatPeer.value?.endpointId == endpointId) {
                            sendAck(endpointId, msgId, "ACK_READ")
                        }
                    }

                    "ACK_DELIVERED" -> {
                        val msgId = json.getString("id")
                        updateMessageStatus(endpointId, msgId, MessageStatus.DELIVERED)
                    }

                    "ACK_READ" -> {
                        val msgId = json.getString("id")
                        updateMessageStatus(endpointId, msgId, MessageStatus.READ)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendAck(endpointId: String, msgId: String, type: String) {
        val ackJson = JSONObject().apply {
            put("type", type)
            put("id", msgId)
        }
        val payload = Payload.fromBytes(ackJson.toString().toByteArray(StandardCharsets.UTF_8))
        Nearby.getConnectionsClient(this).sendPayload(endpointId, payload)
    }

    private fun sendReadReceiptsForPeer(endpointId: String) {
        val messages = _chatHistoryMap.value[endpointId] ?: return
        messages.filter { !it.isFromMe }.forEach { msg ->
            sendAck(endpointId, msg.id, "ACK_READ")
        }
    }

    private fun updateMessageStatus(endpointId: String, msgId: String, newStatus: MessageStatus) {
        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = currentHistory[endpointId]?.toMutableList() ?: return

        val index = peerMessages.indexOfFirst { it.id == msgId }
        if (index != -1) {
            val existing = peerMessages[index]
            if (newStatus.ordinal > existing.status.ordinal) {
                peerMessages[index] = existing.copy(status = newStatus)
                currentHistory[endpointId] = peerMessages
                _chatHistoryMap.value = currentHistory
            }
        }
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
        stopAutoRefreshLoop()
        stopP2PDiscovery()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}