package com.ncorti.kotlin.template.app

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.github.mjdev.libaums.UsbMassStorageDevice
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.fs.UsbFileInputStream

class BackgroundService : Service() {
    private lateinit var usbManager: UsbManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val connectedDevices = ConcurrentHashMap<String, UsbDevice>()
    private val isServiceRunning = AtomicBoolean(false)
    private val activeTransfers = ConcurrentHashMap<String, AtomicBoolean>()

    companion object {
        private const val TAG = "USBService"
        private const val ACTION_USB_PERMISSION = "com.androUsb.USB_PERMISSION"
        private val SUPPORTED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "mp4", "mp3",
            "doc", "docx", "pdf", "txt", "zip", "rar"
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handleUsbPermission(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleDeviceAttached(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDeviceDetached(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeService()
    }

    private fun initializeService() {
        try {
            usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            notificationHelper = NotificationHelper(this)
            initializeWakeLock()
            registerUsbReceiver()
            startForeground(NotificationHelper.NOTIFICATION_ID, notificationHelper.createForegroundNotification())
            isServiceRunning.set(true)
            checkForAlreadyConnectedDevices()
            logEvent("Service initialized successfully")
        } catch (e: Exception) {
            logError("Service initialization failed", e)
            stopSelf()
        }
    }

    private fun initializeWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "USBService::BackgroundServiceLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 minutes
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
    }

    private fun handleUsbPermission(intent: Intent) {
        synchronized(this) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                device?.let {
                    logEvent("USB Permission granted for device: ${it.deviceName}")
                    processUsbDevice(it)
                }
            } else {
                logEvent("USB Permission denied for device: ${device?.deviceName}")
            }
        }
    }

    private fun handleDeviceAttached(intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let {
            if (!connectedDevices.containsKey(it.deviceName)) {
                logEvent("USB Device attached: ${it.deviceName}")
                requestUsbPermission(it)
            }
        }
    }

    private fun handleDeviceDetached(intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let {
            logEvent("USB Device detached: ${it.deviceName}")
            activeTransfers[it.deviceName]?.set(false)
            connectedDevices.remove(it.deviceName)
            cleanupDevice(it)
        }
    }

    private fun processUsbDevice(device: UsbDevice) {
        serviceScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logError("Error processing USB device", throwable as Exception)
        }) {
            try {
                // Register the device and prepare destination folder
                connectedDevices[device.deviceName] = device
                activeTransfers[device.deviceName] = AtomicBoolean(true)
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val destinationDir = File(getExternalFilesDir(null), "usb/${device.deviceName}_$timestamp").apply { mkdirs() }
                processUsbMassStorage(destinationDir)
            } catch (e: Exception) {
                logError("Error processing USB device: ${device.deviceName}", e)
                scheduleRetry(device)
            }
        }
    }

    // Using libaums to initialize and scan the USB mass storage
    private fun processUsbMassStorage(destinationDir: File) {
        try {
            val storageDevices = UsbMassStorageDevice.getMassStorageDevices(this)
            for (massStorage in storageDevices) {
                massStorage.init()
                val partitions = massStorage.partitions
                if (partitions.isNotEmpty()) {
                    val fileSystem = partitions[0].fileSystem
                    val root = fileSystem.rootDirectory
                    scanAndCopyFiles(root, destinationDir)
                }
            }
        } catch (e: Exception) {
            logError("Error processing USB storage", e)
        }
    }

    // Scans the USB storage recursively and copies supported files
    private fun scanAndCopyFiles(root: UsbFile, destinationDir: File) {
        val stack = Stack<UsbFile>()
        stack.push(root)

        while (stack.isNotEmpty()) {
            val currentDir = stack.pop()
            for (file in currentDir.listFiles()) {
                if (file.isDirectory) {
                    stack.push(file)
                } else if (isSupportedFile(file.name)) {
                    val targetFile = File(destinationDir, file.name)
                    copyUsbFile(file, targetFile)
                }
            }
        }
    }

    private fun isSupportedFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return SUPPORTED_EXTENSIONS.contains(extension)
    }

    // Copies an individual file using buffered streams
    private fun copyUsbFile(sourceFile: UsbFile, targetFile: File) {
        try {
            UsbFileInputStream(sourceFile).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            logEvent("Copied: ${sourceFile.name}")
        } catch (e: Exception) {
            logError("Error copying file: ${sourceFile.name}", e)
        }
    }

    private fun scheduleRetry(device: UsbDevice) {
        scheduleRetry(device.deviceName)
    }

    private fun scheduleRetry(deviceName: String) {
        UsbRetryWorker.schedule(this, deviceName)
    }

    private fun cleanupDevice(device: UsbDevice) {
        try {
            activeTransfers[device.deviceName]?.set(false)
            WorkManager.getInstance(this).cancelUniqueWork("usb_retry_${device.deviceName}")
        } catch (e: Exception) {
            logError("Error cleaning up device: ${device.deviceName}", e)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun checkForAlreadyConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (!connectedDevices.containsKey(device.deviceName)) {
                requestUsbPermission(device)
            }
        }
    }

    private fun logEvent(message: String) {
        Log.d(TAG, message)
        try {
            val logFile = File(getExternalFilesDir(null), "log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log: ${e.message}")
        }
    }

    private fun logError(message: String, error: Exception?) {
        Log.e(TAG, message, error)
        try {
            val logFile = File(getExternalFilesDir(null), "log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val errorMessage = error?.message ?: "Unknown error"
            logFile.appendText("[$timestamp] ERROR: $message - $errorMessage\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing error log: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isServiceRunning.get()) {
            initializeService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            serviceScope.cancel()
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
            }
            unregisterReceiver(usbReceiver)
            isServiceRunning.set(false)
            connectedDevices.clear()
            activeTransfers.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during destruction", e)
        }
    }
}

class NotificationHelper(private val context: Context) {
    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "usb_service_channel"
        private const val CHANNEL_NAME = "USB Service"
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("USB Service Running")
            .setContentText("Monitoring USB devices in the background.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

class UsbRetryWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    companion object {
        fun schedule(context: Context, deviceName: String) {
            val work = OneTimeWorkRequestBuilder<UsbRetryWorker>()
                .setInputData(workDataOf("deviceName" to deviceName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "usb_retry_$deviceName",
                ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }

    override fun doWork(): Result {
        Log.d("UsbRetryWorker", "Retrying USB processing for device: ${inputData.getString("deviceName")}")
        // Implement retry logic if needed.
        return Result.success()
    }
}