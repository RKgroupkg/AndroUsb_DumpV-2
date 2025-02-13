package com.ncorti.kotlin.template.app

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class BackgroundService : Service() {
    private lateinit var usbManager: UsbManager
    private val connectedDevices = mutableMapOf<String, UsbDevice>()
    private var isServiceRunning = false
    
    companion object {
        private const val TAG = "USBService"
        private const val ACTION_USB_PERMISSION = "com.androUsb.USB_PERMISSION"
        private const val BUFFER_SIZE = 1024 * 1024 * 8 // 8MB buffer size
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                handleUsbDevice(it)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (!connectedDevices.containsKey(it.deviceName)) {
                            requestUsbPermission(it)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        connectedDevices.remove(it.deviceName)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        isServiceRunning = true
        checkForAlreadyConnectedDevices()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            isServiceRunning = true
            checkForAlreadyConnectedDevices()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering USB receiver: ${e.message}")
        }
        isServiceRunning = false
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
    }

    private fun checkForAlreadyConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (!connectedDevices.containsKey(device.deviceName)) {
                requestUsbPermission(device)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handleUsbDevice(device: UsbDevice) {
        if (connectedDevices.containsKey(device.deviceName)) {
            return
        }

        connectedDevices[device.deviceName] = device
        thread(start = true, priority = Thread.MAX_PRIORITY) {
            try {
                performBackup(device)
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed for device ${device.deviceName}: ${e.message}")
            }
        }
    }

    private fun performBackup(device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backupDir = createBackupDirectory()
        val deviceDir = File(backupDir, "${device.deviceName}_$timestamp")
        
        try {
            if (!deviceDir.exists() && !deviceDir.mkdirs()) {
                Log.e(TAG, "Failed to create backup directory")
                return
            }

            if (isMassStorageDevice(device)) {
                backupMassStorageDevice(connection, device, deviceDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
        } finally {
            connection.close()
        }
    }

    private fun createBackupDirectory(): File {
        val backupDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "UsbBackups"
        )
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        return backupDir
    }

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        return device.interfaceCount > 0 && device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
    }

    private fun backupMassStorageDevice(connection: UsbDeviceConnection, device: UsbDevice, destDir: File) {
        val interface0 = device.getInterface(0)
        if (!connection.claimInterface(interface0, true)) {
            Log.e(TAG, "Failed to claim interface")
            return
        }

        try {
            var totalBytesCopied = 0L
            val endpointCount = interface0.endpointCount
            
            for (i in 0 until endpointCount) {
                val endpoint = interface0.getEndpoint(i)
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    val buffer = ByteArray(BUFFER_SIZE)
                    val outputFile = File(destDir, "backup_${System.currentTimeMillis()}.bin")
                    
                    FileOutputStream(outputFile).use { fos ->
                        val channel = fos.channel
                        var bytesRead: Int
                        
                        while (connection.bulkTransfer(endpoint, buffer, buffer.size, 5000)
                            .also { bytesRead = it } > 0) {
                            channel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                            totalBytesCopied += bytesRead
                        }
                    }
                }
            }
            
            Log.d(TAG, "Backup completed: $totalBytesCopied bytes copied")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during backup: ${e.message}")
        } finally {
            try {
                connection.releaseInterface(interface0)
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing interface: ${e.message}")
            }
        }
    }

    private fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
}
