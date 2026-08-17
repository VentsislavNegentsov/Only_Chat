package com.example.chatfans

import android.Manifest
import android.app.Activity
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.chatfans.ui.theme.ChatFansTheme
import com.example.chatfans.ui.theme.ChatFansBlue
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

data class FanProfile(
    val firstName: String = "",
    val lastName: String = "",
    val stageName: String = "",
    val email: String = "",
    val phone: String = "",
    val profilePhotoPath: String? = null
)

data class PeerDevice(
    val endpointId: String,
    val name: String,
    val isChatFansActive: Boolean = true,
    val signalQuality: String = "LEVEL_1",
    val profile: FanProfile? = null
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
    private const val PREFS_NAME = "chatfans_prefs"
    private const val KEY_PROFILE = "user_profile"

    fun saveProfile(context: Context, profile: FanProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("firstName", profile.firstName)
            put("lastName", profile.lastName)
            put("stageName", profile.stageName)
            put("email", profile.email)
            put("phone", profile.phone)
            put("profilePhotoPath", profile.profilePhotoPath ?: "")
        }
        prefs.edit().putString(KEY_PROFILE, json.toString()).apply()
    }

    fun loadProfile(context: Context): FanProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            FanProfile(
                firstName = json.optString("firstName"),
                lastName = json.optString("lastName"),
                stageName = json.optString("stageName"),
                email = json.optString("email"),
                phone = json.optString("phone"),
                profilePhotoPath = json.optString("profilePhotoPath").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }

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

    fun saveReceivedProfiles(context: Context, profiles: Map<String, FanProfile>) {
        try {
            val root = JSONObject()
            for ((peerName, p) in profiles) {
                val obj = JSONObject().apply {
                    put("firstName", p.firstName)
                    put("lastName", p.lastName)
                    put("stageName", p.stageName)
                    put("email", p.email)
                    put("phone", p.phone)
                    put("profilePhotoPath", p.profilePhotoPath ?: "")
                }
                root.put(peerName, obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("received_profiles", root.toString()).apply()
        } catch (e: Exception) {}
    }

    fun loadReceivedProfiles(context: Context): Map<String, FanProfile> {
        val map = mutableMapOf<String, FanProfile>()
        try {
            val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("received_profiles", null) ?: return map
            val root = JSONObject(jsonStr)
            val keys = root.keys()
            while (keys.hasNext()) {
                val peerName = keys.next()
                val obj = root.getJSONObject(peerName)
                val p = FanProfile(
                    firstName = obj.optString("firstName"),
                    lastName = obj.optString("lastName"),
                    stageName = obj.optString("stageName"),
                    email = obj.optString("email"),
                    phone = obj.optString("phone"),
                    profilePhotoPath = obj.optString("profilePhotoPath").takeIf { it.isNotEmpty() }
                )
                map[peerName] = p
            }
        } catch (e: Exception) {}
        return map
    }
}

object ImageUtils {
    fun copyUriToCacheFile(context: Context, uri: Uri): File? {
        return try {
            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            
            // Target: 480p (854 pixels on the long side)
            val targetMaxDim = 854
            
            // 1. Read dimensions
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            
            // 2. Efficiently downsample while decoding to save RAM
            var inSampleSize = 1
            if (options.outHeight > targetMaxDim || options.outWidth > targetMaxDim) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= targetMaxDim && halfWidth / inSampleSize >= targetMaxDim) {
                    inSampleSize *= 2
                }
            }
            
            // 3. Decode to bitmap
            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val sourceBitmap = context.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, decodeOptions) 
            } ?: return null
            
            // 4. Calculate final dimensions keeping aspect ratio
            val scale = targetMaxDim.toFloat() / Math.max(sourceBitmap.width, sourceBitmap.height)
            val finalWidth = (sourceBitmap.width * scale).toInt()
            val finalHeight = (sourceBitmap.height * scale).toInt()
            
            val resizedBitmap = Bitmap.createScaledBitmap(sourceBitmap, finalWidth, finalHeight, true)
            
            // 5. Compress to JPEG (80% quality) and save
            FileOutputStream(file).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            
            // Cleanup memory
            sourceBitmap.recycle()
            if (resizedBitmap != sourceBitmap) resizedBitmap.recycle()
            
            if (file.exists()) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun processProfilePhoto(context: Context, uri: Uri): String? {
        return try {
            val file = File(context.filesDir, "profile_photo.jpg")
            
            // Target: 240p (approx 426x240)
            val targetMaxDim = 426
            
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            
            var inSampleSize = 1
            if (options.outHeight > targetMaxDim || options.outWidth > targetMaxDim) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= targetMaxDim && halfWidth / inSampleSize >= targetMaxDim) {
                    inSampleSize *= 2
                }
            }
            
            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val sourceBitmap = context.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, decodeOptions) 
            } ?: return null
            
            val scale = targetMaxDim.toFloat() / Math.max(sourceBitmap.width, sourceBitmap.height)
            val finalWidth = (sourceBitmap.width * scale).toInt()
            val finalHeight = (sourceBitmap.height * scale).toInt()
            
            val resizedBitmap = Bitmap.createScaledBitmap(sourceBitmap, finalWidth, finalHeight, true)
            
            FileOutputStream(file).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            sourceBitmap.recycle()
            if (resizedBitmap != sourceBitmap) resizedBitmap.recycle()
            
            file.absolutePath
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
            ChatFansTheme {
                val peers by ChatService.discoveredPeers.collectAsState()
                val chatHistoryMap by ChatService.chatHistoryMap.collectAsState()
                val activeChatPeer by ChatService.activeChatPeer.collectAsState()
                val userProfile by ChatService.userProfile.collectAsState()
                val context = LocalContext.current
                
                var isEditingProfile by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (userProfile == null || isEditingProfile) {
                        ProfileSetupScreen(
                            initialProfile = userProfile ?: FanProfile(),
                            onSave = { profile ->
                                ChatStorageHelper.saveProfile(context, profile)
                                ChatService.setUserProfile(profile)
                                isEditingProfile = false
                            },
                            onCancel = if (userProfile != null) { { isEditingProfile = false } } else null
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainScreen(
                                peers = peers,
                                chatHistoryMap = chatHistoryMap,
                                userProfile = userProfile,
                                onDeviceClick = { peer -> ChatService.setActiveChatPeer(peer) },
                                onHuntClick = { moveTaskToBack(true) },
                                onEditProfileClick = { isEditingProfile = true },
                                onExitClick = {
                                    ChatService.stopService(context)
                                    (context as? Activity)?.finishAffinity()
                                }
                            )

                            activeChatPeer?.let { peer ->
                                val peerMessages = chatHistoryMap[peer.name] ?: emptyList()

                                ChatFullScreenWindow(
                                    peer = peer,
                                    messages = peerMessages,
                                    onDismiss = { ChatService.setActiveChatPeer(null) },
                                    onSendMessage = { text -> ChatService.sendMessage(context, peer.endpointId, peer.name, text) },
                                    onSendProfile = { ChatService.sendUserProfile(context, peer.endpointId, peer.name) },
                                    onSendPhotoUri = { uri -> ChatService.sendPhotoUri(context, peer.endpointId, peer.name, uri) }
                                )
                            }
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
fun ProfileSetupScreen(
    initialProfile: FanProfile,
    onSave: (FanProfile) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var firstName by remember { mutableStateOf(initialProfile.firstName) }
    var lastName by remember { mutableStateOf(initialProfile.lastName) }
    var stageName by remember { mutableStateOf(initialProfile.stageName) }
    var email by remember { mutableStateOf(initialProfile.email) }
    var phone by remember { mutableStateOf(initialProfile.phone) }
    var photoPath by remember { mutableStateOf(initialProfile.profilePhotoPath) }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = TakePictureWithFrontCamera()
    ) { success ->
        if (success && tempCameraUri != null) {
            val savedPath = ImageUtils.processProfilePhoto(context, tempCameraUri!!)
            if (savedPath != null) photoPath = savedPath
        }
    }

    if (onCancel != null) {
        BackHandler(onBack = onCancel)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatFansLogo(modifier = Modifier.size(48.dp))
            Text(
                text = if (onCancel == null) "Create Profile" else "ChatFans Profile",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black
            )
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = ChatFansBlue)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Profile Photo Section
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color.LightGray.copy(alpha = 0.3f))
                .clickable {
                    try {
                        val photoFile = File(context.cacheDir, "profile_temp.jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            photoFile
                        )
                        tempCameraUri = uri
                        cameraLauncher.launch(uri)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (photoPath != null) {
                val bitmap = remember(photoPath) { ImageUtils.loadBitmapFromFile(photoPath!!) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Profile Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = "Take Photo",
                    modifier = Modifier.size(64.dp),
                    tint = Color.Gray
                )
            }
            
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Text(
                    "Selfie",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.wrapContentSize(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val textFieldModifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        val supportingText: @Composable () -> Unit = { Text("Not mandatory", fontSize = 10.sp, color = Color.Gray) }

        OutlinedTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = { Text("First Name", fontSize = 12.sp) },
            modifier = textFieldModifier,
            shape = RoundedCornerShape(12.dp),
            supportingText = supportingText,
            singleLine = true
        )
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Last Name", fontSize = 12.sp) },
            modifier = textFieldModifier,
            shape = RoundedCornerShape(12.dp),
            supportingText = supportingText,
            singleLine = true
        )
        OutlinedTextField(
            value = stageName,
            onValueChange = { stageName = it },
            label = { Text("Stage Name / Display Name", fontSize = 12.sp) },
            modifier = textFieldModifier,
            shape = RoundedCornerShape(12.dp),
            supportingText = { Text("Recommended for discovery", fontSize = 10.sp, color = ChatFansBlue) },
            singleLine = true
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email Address", fontSize = 12.sp) },
            modifier = textFieldModifier,
            shape = RoundedCornerShape(12.dp),
            supportingText = supportingText,
            singleLine = true
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number", fontSize = 12.sp) },
            modifier = textFieldModifier,
            shape = RoundedCornerShape(12.dp),
            supportingText = supportingText,
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (stageName.isNotBlank()) {
                    onSave(FanProfile(firstName, lastName, stageName, email, phone, photoPath))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ChatFansBlue)
        ) {
            Text(if (onCancel == null) "Complete Setup" else "Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ChatFansLogo(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            // 1. Draw circle background
            drawCircle(
                color = ChatFansBlue,
                radius = canvasWidth / 2f
            )
            
            val strokeWidth = canvasWidth * 0.08f
            val circleRadius = canvasWidth * 0.25f
            
            // 2. Draw 'O' (left circle)
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(canvasWidth * 0.38f, canvasHeight * 0.5f),
                style = Stroke(width = strokeWidth)
            )
            
            // 3. Draw 'C' (right arc) interlocking
            val cRect = Rect(
                left = canvasWidth * 0.40f,
                top = canvasHeight * 0.25f,
                right = canvasWidth * 0.90f,
                bottom = canvasHeight * 0.75f
            )
            
            drawPath(
                path = Path().apply {
                    addArc(cRect, 40f, 280f)
                },
                color = Color.White,
                style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            
            // 4. Draw the 'Eye' dot in the middle of the interlocking area
            drawCircle(
                color = Color.White,
                radius = strokeWidth / 2f,
                center = Offset(canvasWidth * 0.54f, canvasHeight * 0.5f),
                style = Fill
            )
        }
    }
}

@Composable
fun ScanningIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
            // Pulse circle
            Surface(
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = (1f - (scale - 1f)).coerceIn(0f, 1f)
                    ),
                shape = CircleShape,
                color = Color(0xFF2E7D32).copy(alpha = 0.4f)
            ) {}
            // Static inner dot
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = Color(0xFF2E7D32)
            ) {}
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Searching for nearby fans...",
            fontSize = 14.sp,
            color = Color(0xFF2E7D32).copy(alpha = alpha),
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun MainScreen(
    peers: List<PeerDevice>,
    chatHistoryMap: Map<String, List<ChatMessage>>,
    userProfile: FanProfile?,
    onDeviceClick: (PeerDevice) -> Unit,
    onHuntClick: () -> Unit,
    onEditProfileClick: () -> Unit,
    onExitClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showImportantDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding() // Move content down for camera cutout
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatFansLogo(modifier = Modifier.size(42.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ChatFans",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    color = ChatFansBlue
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEditProfileClick) {
                    if (userProfile?.profilePhotoPath != null) {
                        val bitmap = remember(userProfile.profilePhotoPath) { 
                            ImageUtils.loadBitmapFromFile(userProfile.profilePhotoPath) 
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "My Profile",
                                modifier = Modifier.size(24.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } ?: Icon(Icons.Default.AccountCircle, contentDescription = "Edit Profile", tint = ChatFansBlue)
                    } else {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Edit Profile", tint = ChatFansBlue)
                    }
                }
                IconButton(onClick = onExitClick) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Exit", tint = Color.Red)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.Gray)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                showMenu = false
                                showAboutDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Important") },
                            onClick = {
                                showMenu = false
                                showImportantDialog = true
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = "by Ventsislav Negentsov",
            fontSize = 12.sp, // Slightly bigger
            color = Color.Gray,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 2.dp) // Leftmost
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Button(
                onClick = onHuntClick,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChatFansBlue,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Run in Background",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            ScanningIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for nearby fans to appear...", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(peers) { peer ->
                    val hasHistory = chatHistoryMap[peer.name]?.isNotEmpty() == true
                    DeviceCard(peer = peer, hasHistory = hasHistory, onClick = { onDeviceClick(peer) })
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    if (showImportantDialog) {
        ImportantDialog(onDismiss = { showImportantDialog = false })
    }
}

@Composable
fun ImportantDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Understood", color = ChatFansBlue)
            }
        },
        title = {
            Text("Important: Battery Settings", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Important about battery restriction settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Android phones are designed to aggressively save power by putting background apps to sleep. Because OnlyChat relies on continuous, real-time local discovery to find people nearby while your phone is in your pocket (using Hide & Wait), battery restrictions will cause the radar to stop scanning.\n\n" +
                           "By removing battery restrictions, you ensure that your connection service stays fully active and listening in the background—so you never miss a match while out in public. Your privacy remains completely protected, and power is used solely to keep your local radar running.",
                    fontSize = 14.sp
                )
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ChatFansBlue)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatFansLogo(modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("About ChatFans", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = "by Ventsislav Negentsov",
                    fontSize = 11.sp,
                    color = ChatFansBlue,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Welcome to ChatFans—your modern, off-grid radar for real-world connection, inspired by the classic Japanese proximity-dating phenomenon (Lovegety). Whether you are navigating a bustling mall, riding the subway, or hanging out at a bar, this app helps you discover, flirt, and connect with people in your immediate perimeter without relying on cellular data or an internet connection.",
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "🚀 How It Works", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = "Off-Grid P2P Mesh: Uses local Bluetooth and Wi-Fi Direct technologies to establish a direct device-to-device network completely independent of cell towers or routers.\n\n" +
                           "Live Radar Scan: Continuously sweeps your surroundings for active peers who are nearby and ready to socialize.\n\n" +
                           "Dynamic Signal Meter: Monitors live connection quality (📶 Strong, 📶 Fair, 📶 Weak) based on real-time link feedback, giving you an idea of how close a match might be.",
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "💬 Key Features", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = "Direct Messaging & Emojis: Break the ice with instant text messaging and a quick emoji picker.\n\n" +
                           "Photo & Selfie Sharing: Share memorable snapshots straight from your gallery or capture a quick front-camera selfie.\n\n" +
                           "Fan Profiling: Share your personal profile (Name, Email, etc.) using the profile feature.\n\n" +
                           "Lockscreen Alerts: Receive instant notifications, sounds, and vibrations the moment a new fan enters your radar range.\n\n" +
                           "Background Mode: Quickly minimize or push the app to the background using the dedicated 🟢 Run in Background button when you want to keep a low profile.",
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "💡 How to Use", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = "Enable Radio & Location: Ensure your Bluetooth and Location services are turned on so the app can scan your local surroundings.\n\n" +
                           "Scan & Discover: Keep the app open or running in the background; discovered peers will automatically appear on your main screen list.\n\n" +
                           "Connect & Chat: Tap on any active device card to open the full-screen chat window, say hello, and start flirting!",
                    fontSize = 14.sp
                )
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun DeviceCard(
    peer: PeerDevice,
    hasHistory: Boolean,
    onClick: () -> Unit
) {
    val profile = peer.profile
    val nameParts = remember(peer.name) { peer.name.split("|") }
    
    val displayName = profile?.stageName?.takeIf { it.isNotBlank() } ?: (nameParts.firstOrNull() ?: peer.name)
    val subInfo = if (profile != null) {
        "${profile.firstName} ${profile.lastName}".trim().takeIf { it.isNotBlank() } ?: "ChatFans Member"
    } else {
        nameParts.getOrNull(1) ?: ""
    }

    val cardBgColor = if (hasHistory) Color(0xFFF0F9FF) else MaterialTheme.colorScheme.surface
    val borderColor = if (hasHistory) ChatFansBlue.copy(alpha = 0.4f) else Color.LightGray.copy(alpha = 0.3f)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (profile?.profilePhotoPath != null) {
                    val bitmap = remember(profile.profilePhotoPath) { ImageUtils.loadBitmapFromFile(profile.profilePhotoPath) }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Profile Photo",
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.LightGray.copy(alpha = 0.2f)),
                            contentScale = ContentScale.Crop
                        )
                    } ?: ProfileIconPlaceholder(displayName)
                } else if (profile != null) {
                    ProfileIconPlaceholder(displayName)
                }

                if (profile != null) Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black
                    )

                    if (subInfo.isNotBlank()) {
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = subInfo,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(9.dp),
                            shape = CircleShape,
                            color = if (peer.isChatFansActive) ChatFansBlue else Color.Gray
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (peer.isChatFansActive) "ChatFans Active" else "Disconnected",
                            fontSize = 12.sp,
                            color = if (peer.isChatFansActive) ChatFansBlue else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                if (!hasHistory) {
                    Surface(
                        color = Color.Red,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "NEW",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                SignalIndicator(quality = peer.signalQuality)
            }
        }
    }
}

@Composable
fun ProfileIconPlaceholder(name: String) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ChatFansBlue.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = ChatFansBlue,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp
        )
    }
}

@Composable
fun SignalIndicator(quality: String) {
    val level = when (quality) {
        "LEVEL_5" -> 5
        "LEVEL_4" -> 4
        "LEVEL_3" -> 3
        "LEVEL_2" -> 2
        "LEVEL_1" -> 1
        "📶 Strong" -> 5 // Fallback for legacy
        "📶 Fair" -> 3   // Fallback for legacy
        "📶 Weak" -> 1   // Fallback for legacy
        else -> 0
    }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..5) {
            val barHeight = (i * 3).dp
            val color = if (i <= level) ChatFansBlue else Color.LightGray.copy(alpha = 0.3f)
            Surface(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight),
                shape = RoundedCornerShape(1.dp),
                color = color
            ) {}
        }
    }
}

@Composable
fun ChatFullScreenWindow(
    peer: PeerDevice,
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendProfile: () -> Unit,
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
                        Text("←", fontSize = 26.sp, fontWeight = FontWeight.Black, color = ChatFansBlue)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = cleanPeerName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollbar(listState, width = 4.dp, color = ChatFansBlue),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.Bottom)
                ) {
                    items(messages) { msg ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (msg.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Surface(
                                color = if (msg.isFromMe) ChatFansBlue else Color(0xFFF0F0F0),
                                shape = RoundedCornerShape(
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomStart = if (msg.isFromMe) 20.dp else 4.dp,
                                    bottomEnd = if (msg.isFromMe) 4.dp else 20.dp
                                ),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    if (msg.imagePath != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 400.dp) // Adaptive height up to 400dp
                                                .clip(RoundedCornerShape(16.dp))
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
                                                    contentScale = ContentScale.Fit
                                                )
                                            }

                                            if (msg.progress != null && msg.progress < 1f) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.75f))
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = "Uploading... ${(msg.progress * 100).toInt()}%",
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        LinearProgressIndicator(
                                                            progress = { msg.progress },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(6.dp)
                                                                .clip(CircleShape),
                                                            color = Color.White,
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
                                            fontSize = 17.sp,
                                            lineHeight = 24.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTimestamp(msg.timestamp),
                                            fontSize = 10.sp,
                                            color = if (msg.isFromMe) Color.White.copy(alpha = 0.8f) else Color.Gray
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

                Spacer(modifier = Modifier.height(10.dp))

                if (showEmojiPicker) {
                    Surface(
                        color = Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            commonEmojis.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 26.sp,
                                    modifier = Modifier
                                        .clickable { textInput += emoji }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { photoGalleryLauncher.launch("image/*") },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ChatFansBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ChatFansBlue),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("📷 Gallery", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
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
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ChatFansBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ChatFansBlue),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("🤳 Selfie", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    IconButton(
                        onClick = onSendProfile,
                        modifier = Modifier
                            .size(40.dp)
                            .background(ChatFansBlue.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Text("👤", fontSize = 20.sp)
                    }

                    IconButton(
                        onClick = { showEmojiPicker = !showEmojiPicker },
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (showEmojiPicker) ChatFansBlue else ChatFansBlue.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Text("😊", fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Write a message...", fontSize = 15.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChatFansBlue,
                            unfocusedBorderColor = Color.LightGray
                        ),
                        minLines = 1,
                        maxLines = 5
                    )

                    FloatingActionButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput)
                                textInput = ""
                            }
                        },
                        containerColor = ChatFansBlue,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(54.dp)
                    ) {
                        Text("➤", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            fullScreenImagePath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.98f))
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
        MessageStatus.SENT -> Pair("✓", Color.White.copy(alpha = 0.7f))
        MessageStatus.DELIVERED -> Pair("✓✓", Color.White.copy(alpha = 0.95f))
        MessageStatus.READ -> Pair("✓✓", Color.White)
    }

    Text(
        text = symbol,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
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
        const val SERVICE_ID = "com.example.chatfans.P2P_CHAT"
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

        private val _userProfile = MutableStateFlow<FanProfile?>(null)
        val userProfile: StateFlow<FanProfile?> = _userProfile

        private val _receivedProfiles = MutableStateFlow<Map<String, FanProfile>>(emptyMap())
        val receivedProfiles: StateFlow<Map<String, FanProfile>> = _receivedProfiles

        private val payloadMsgMap = mutableMapOf<Long, Pair<String, String>>()
        private val incomingPhotoMeta = mutableMapOf<Long, Pair<String, String>>()
        private val incomingProfilePhotoMeta = mutableMapOf<Long, String>() // payloadId -> peerName

        private val peerLastSeenMap = mutableMapOf<String, Long>()
        private val peerFirstSeenMap = mutableMapOf<String, Long>()
        private val candidatePeersMap = mutableMapOf<String, PeerDevice>()
        private val notifiedPeersSet = mutableSetOf<String>()
        private val pingSentMap = mutableMapOf<String, Long>()

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

        fun setUserProfile(profile: FanProfile) {
            _userProfile.value = profile
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

        fun sendUserProfile(context: Context, endpointId: String, peerName: String) {
            val profile = _userProfile.value ?: return
            
            val msgId = UUID.randomUUID().toString()
            val json = JSONObject().apply {
                put("type", "PROFILE_CARD")
                put("id", msgId)
                put("firstName", profile.firstName)
                put("lastName", profile.lastName)
                put("stageName", profile.stageName)
                put("email", profile.email)
                put("phone", profile.phone)
            }

            val metaPayload = Payload.fromBytes(json.toString().toByteArray(StandardCharsets.UTF_8))
            val client = Nearby.getConnectionsClient(context)
            client.sendPayload(endpointId, metaPayload)

            profile.profilePhotoPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val filePayload = Payload.fromFile(file)
                    val photoJson = JSONObject().apply {
                        put("type", "PROFILE_PHOTO_META")
                        put("payloadId", filePayload.id)
                    }
                    client.sendPayload(endpointId, Payload.fromBytes(photoJson.toString().toByteArray(StandardCharsets.UTF_8)))
                    client.sendPayload(endpointId, filePayload)
                }
            }

            val briefInfo = "👤 shared profile details"
            sendMessage(context, endpointId, peerName, briefInfo)
        }

        fun sendPhotoUri(context: Context, endpointId: String, peerName: String, uri: Uri) {
            val file = ImageUtils.copyUriToCacheFile(context, uri) ?: return
            instance?.sendPhotoFile(context, endpointId, peerName, file)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ChatService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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
            "ChatFans::P2PWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        createNotificationChannels()

        val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        registerReceiver(airplaneModeReceiver, filter)

        val savedHistory = ChatStorageHelper.loadHistory(this)
        _chatHistoryMap.value = savedHistory

        val savedProfile = ChatStorageHelper.loadProfile(this)
        _userProfile.value = savedProfile

        val savedReceived = ChatStorageHelper.loadReceivedProfiles(this)
        _receivedProfiles.value = savedReceived
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
                        pingSentMap.remove(endpointId)
                        Nearby.getConnectionsClient(this@ChatService).disconnectFromEndpoint(endpointId)
                        continue
                    }

                    if (connectingOrConnected.contains(endpointId)) {
                        val pingJson = JSONObject().apply { put("type", "PING") }
                        pingSentMap[endpointId] = System.currentTimeMillis()
                        val payload = Payload.fromBytes(pingJson.toString().toByteArray(StandardCharsets.UTF_8))
                        Nearby.getConnectionsClient(this@ChatService).sendPayload(endpointId, payload)
                    }
                }

                updateVisiblePeersList()
            }
        }
    }

    private fun updatePeerSignalQuality(endpointId: String, quality: String) {
        val peer = candidatePeersMap[endpointId]
        if (peer != null && peer.signalQuality != quality) {
            candidatePeersMap[endpointId] = peer.copy(signalQuality = quality)
            updateVisiblePeersList()
        }
    }

    private fun updateVisiblePeersList() {
        val now = System.currentTimeMillis()
        val profiles = _receivedProfiles.value

        val stablePeers = candidatePeersMap.values.map { peer ->
            val p = profiles[peer.name]
            if (p != null) peer.copy(profile = p) else peer
        }.filter { peer ->
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
                "ChatFans Alerts & Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when nearby fans are discovered or incoming messages arrive"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "chat_fans_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ChatFans Active", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ChatFans Active")
            .setContentText("Listening for nearby fans and messages...")
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
            .setContentTitle("New Fan Discovered!")
            .setContentText("Found nearby fan: $cleanName")
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

    private fun addOrUpdatePeer(endpointId: String, name: String, isChatFansActive: Boolean = true) {
        if (isSelf(name)) return

        val now = System.currentTimeMillis()
        if (!peerFirstSeenMap.containsKey(endpointId)) {
            peerFirstSeenMap[endpointId] = now
        }
        peerLastSeenMap[endpointId] = now

        val existingQuality = candidatePeersMap[endpointId]?.signalQuality ?: "📶 Strong"
        val peer = PeerDevice(endpointId, name, isChatFansActive, signalQuality = existingQuality)
        candidatePeersMap[endpointId] = peer

        updateVisiblePeersList()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val myName = getDetailedDeviceName()

            if (isSelf(info.endpointName) || info.endpointName == myName) return

            val isChatFans = info.serviceId == SERVICE_ID
            addOrUpdatePeer(endpointId, info.endpointName, isChatFansActive = isChatFans)

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
            pingSentMap.remove(endpointId)
            updateVisiblePeersList()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val myName = getDetailedDeviceName()

            if (isSelf(info.endpointName) || info.endpointName == myName) return

            connectingOrConnected.add(endpointId)
            addOrUpdatePeer(endpointId, info.endpointName, isChatFansActive = true)
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
                pingSentMap.remove(endpointId)
                updateVisiblePeersList()
            }
        }

        override fun onDisconnected(endpointId: String) {
            peerFirstSeenMap.remove(endpointId)
            peerLastSeenMap.remove(endpointId)
            candidatePeersMap.remove(endpointId)
            connectingOrConnected.remove(endpointId)
            notifiedPeersSet.remove(endpointId)
            pingSentMap.remove(endpointId)
            updateVisiblePeersList()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            peerLastSeenMap[endpointId] = System.currentTimeMillis()
            val senderName = candidatePeersMap[endpointId]?.name ?: "Nearby Fan"
            val peer = PeerDevice(endpointId, senderName, isChatFansActive = true)

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
                            val sentTime = pingSentMap.remove(endpointId)
                            if (sentTime != null) {
                                val rtt = System.currentTimeMillis() - sentTime
                                val quality = when {
                                    rtt < 50 -> "LEVEL_5"
                                    rtt < 100 -> "LEVEL_4"
                                    rtt < 200 -> "LEVEL_3"
                                    rtt < 400 -> "LEVEL_2"
                                    else -> "LEVEL_1"
                                }
                                updatePeerSignalQuality(endpointId, quality)
                            }
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

                        "PROFILE_CARD" -> {
                            val newProfile = FanProfile(
                                firstName = json.optString("firstName"),
                                lastName = json.optString("lastName"),
                                stageName = json.optString("stageName"),
                                email = json.optString("email"),
                                phone = json.optString("phone")
                            )
                            val currentProfiles = _receivedProfiles.value.toMutableMap()
                            currentProfiles[senderName] = newProfile
                            _receivedProfiles.value = currentProfiles
                            ChatStorageHelper.saveReceivedProfiles(this@ChatService, currentProfiles)
                            updateVisiblePeersList()
                        }

                        "PROFILE_PHOTO_META" -> {
                            val filePayloadId = json.getLong("payloadId")
                            incomingProfilePhotoMeta[filePayloadId] = senderName
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (payload.type == Payload.Type.FILE) {
                val profilePeerName = incomingProfilePhotoMeta[payload.id]
                if (profilePeerName != null) {
                    val targetFile = File(filesDir, "profile_${profilePeerName.hashCode()}.jpg")
                    val pfd = payload.asFile()?.asParcelFileDescriptor()
                    if (pfd != null) {
                        try {
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val currentProfiles = _receivedProfiles.value.toMutableMap()
                            val existing = currentProfiles[profilePeerName] ?: FanProfile()
                            currentProfiles[profilePeerName] = existing.copy(profilePhotoPath = targetFile.absolutePath)
                            _receivedProfiles.value = currentProfiles
                            ChatStorageHelper.saveReceivedProfiles(this@ChatService, currentProfiles)
                            incomingProfilePhotoMeta.remove(payload.id)
                            updateVisiblePeersList()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    return
                }

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
        pingSentMap.clear()
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
