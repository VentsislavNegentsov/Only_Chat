package com.example.onlychat // Make sure package name matches yours

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.onlychat.ui.theme.OnlyChatTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var isServiceRunning by mutableStateOf(value = false)

    // Request Runtime Permissions dynamically
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && isServiceRunning) {
            startGreenLightService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            OnlyChatTheme {
                val detectedDevices by GreenLightService.detectedDevices.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    GreenLightScreen(
                        isActive = isServiceRunning,
                        detectedDevices = detectedDevices.toList()
                    ) { active ->
                        isServiceRunning = active
                        if (active) {
                            checkAndRequestPermissions()
                            startGreenLightService()
                        } else {
                            stopGreenLightService()
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun startGreenLightService() {
        val intent = Intent(this, GreenLightService::class.java).apply {
            action = GreenLightService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopGreenLightService() {
        val intent = Intent(this, GreenLightService::class.java).apply {
            action = GreenLightService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun GreenLightScreen(
    isActive: Boolean,
    detectedDevices: List<String>,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GREEN LIGHT", fontSize = 26.sp)
        Spacer(modifier = Modifier.height(24.dp))

        // Big Indicator Light
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF00E676) else Color.Gray)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(if (isActive) "SIGNAL ACTIVE" else "SIGNAL OFF", fontSize = 18.sp)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Raise Green Light")
            Switch(checked = isActive, onCheckedChange = onToggle)
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text("Nearby Signals Detected (${detectedDevices.size}):")
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(detectedDevices) { address ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Signal from MAC: $address", modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}