package com.example.onlychat // Make sure package name matches yours

import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GreenLightService : Service() {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    companion object {
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val _detectedDevices = MutableStateFlow<Set<String>>(emptySet())
        val detectedDevices: StateFlow<Set<String>> = _detectedDevices

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startBleOperations()
            }
            ACTION_STOP -> {
                stopBleOperations()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "green_light_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Green Light Background Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GREEN LIGHT Active")
            .setContentText("Broadcasting and scanning for nearby signals...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startBleOperations() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return

        if (!adapter.isEnabled) return

        // 1. Start Advertising
        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .setIncludeDeviceName(false)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        // 2. Start Scanning
        scanner = adapter.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        try {
            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopBleOperations() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: "Unknown Device"
            _detectedDevices.value += address
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBleOperations()
        super.onDestroy()
    }
}