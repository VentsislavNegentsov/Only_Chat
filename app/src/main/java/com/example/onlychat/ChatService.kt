package com.example.onlychat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class MessageStatus { SENT, DELIVERED, READ }

data class PeerDevice(val endpointId: String, val name: String)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val isFromMe: Boolean,
    var status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val base64Image: String? = null
)

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
            val maxDimension = 600
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
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, outputStream)
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

        fun sendWhoAmI(endpointId: String) {
            instance?.let { service ->
                val myDetails = service.getDetailedDeviceName()
                val whoAmIMessage = "👤 Device Info: $myDetails"
                sendMessage(endpointId, whoAmIMessage)
            }
        }

        fun sendPhotoUri(context: Context, endpointId: String, uri: Uri) {
            val base64 = ImageUtils.compressUriToBase64(context, uri) ?: return
            instance?.sendPhotoMessage(endpointId, base64)
        }

        fun sendPhotoBitmap(endpointId: String, bitmap: Bitmap) {
            val base64 = ImageUtils.compressBitmapToBase64(bitmap) ?: return
            instance?.sendPhotoMessage(endpointId, base64)
        }
    }

    private fun sendPhotoMessage(endpointId: String, base64Image: String) {
        val msgId = UUID.randomUUID().toString()

        val json = JSONObject().apply {
            put("type", "PHOTO")
            put("id", msgId)
            put("image", base64Image)
        }

        val payload = Payload.fromBytes(json.toString().toByteArray(StandardCharsets.UTF_8))
        Nearby.getConnectionsClient(this).sendPayload(endpointId, payload)

        val newMessage = ChatMessage(
            id = msgId,
            text = "[Photo]",
            isFromMe = true,
            status = MessageStatus.SENT,
            base64Image = base64Image
        )
        val currentHistory = _chatHistoryMap.value.toMutableMap()
        val peerMessages = (currentHistory[endpointId] ?: emptyList()) + newMessage
        currentHistory[endpointId] = peerMessages
        _chatHistoryMap.value = currentHistory
    }

    private val connectingOrConnected = mutableSetOf<String>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createMessageNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startP2PDiscovery()
            }
            ACTION_REFRESH -> restartDiscovery()
            ACTION_STOP -> {
                stopP2PDiscovery()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // Short device name is REQUIRED to avoid Nearby Connections endpoint name byte limit
    fun getDetailedDeviceName(): String {
        val model = Build.MODEL
        return if (model.length > 20) model.substring(0, 20) else model
    }

    private fun createMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                "incoming_messages_channel",
                "Incoming Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a nearby device sends a message"
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
            .setContentText("Scanning and connected to nearby devices...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun notifyUserAndBringToFront(senderName: String, messagePreview: String) {
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
        manager.notify(System.currentTimeMillis().toInt(), notification)

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

                        notifyUserAndBringToFront(senderName, text)
                    }

                    "PHOTO" -> {
                        val msgId = json.getString("id")
                        val base64Image = json.getString("image")
                        val senderName = _discoveredPeers.value.find { it.endpointId == endpointId }?.name ?: "Nearby Peer"

                        val newMessage = ChatMessage(id = msgId, text = "[Photo]", isFromMe = false, base64Image = base64Image)
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

                        notifyUserAndBringToFront(senderName, "📷 Sent you a photo")
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
        stopP2PDiscovery()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}