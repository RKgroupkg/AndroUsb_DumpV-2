import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext

class BackgroundService : Service(), CoroutineScope {
    private lateinit var logger: Logger
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    companion object {
        private const val CHANNEL_ID = "BackgroundServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val BUFFER_SIZE = 8192 // 8KB buffer size
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) {
                val usbPath = intent.data?.path
                usbPath?.let {
                    launch { handleUsbMount(it) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupLogger()
        createNotificationChannel()
        startForeground()
        registerUsbReceiver()
        logger.info("Background service started")
    }

    private fun setupLogger() {
        val logDir = File(getExternalFilesDir(null), "logs")
        logger = Logger(logDir)
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Backup Service")
            .setContentText("Monitoring for USB devices")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply {
            addDataScheme("file")
        }
        registerReceiver(usbReceiver, filter)
    }

    private suspend fun handleUsbMount(usbPath: String) = withContext(Dispatchers.IO) {
        logger.info("USB device mounted at: $usbPath")
        val stats = BackupStats()
        
        try {
            val usbDirectory = File(usbPath)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupDir = File(
                Environment.getExternalStorageDirectory(),
                "UsbBackup/${getUsbName(usbPath)}_$timestamp"
            )

            if (!backupDir.exists() && !backupDir.mkdirs()) {
                throw IOException("Failed to create backup directory: ${backupDir.path}")
            }

            processDirectory(usbDirectory, backupDir, stats)
            logger.info(stats.toString())
            
            updateNotification("Backup completed: ${stats.copiedFiles} files copied")
        } catch (e: Exception) {
            logger.error("Backup failed", e)
            updateNotification("Backup failed: ${e.message}")
        }
    }

    private suspend fun processDirectory(
        sourceDir: File,
        destDir: File,
        stats: BackupStats
    ) = withContext(Dispatchers.IO) {
        sourceDir.listFiles()?.forEach { file ->
            when {
                file.isFile -> processFile(file, destDir, stats)
                file.isDirectory -> {
                    val newDestDir = File(destDir, file.name)
                    if (newDestDir.mkdirs()) {
                        processDirectory(file, newDestDir, stats)
                    }
                }
            }
        }
    }

    private suspend fun processFile(
        sourceFile: File,
        destDir: File,
        stats: BackupStats
    ) = withContext(Dispatchers.IO) {
        stats.totalFiles++
        
        if (!FileExtensionUtils.isSupported(sourceFile.name)) {
            stats.skippedFiles++
            logger.info("Skipped unsupported file: ${sourceFile.name}")
            return@withContext
        }

        try {
            val destFile = File(destDir, sourceFile.name)
            copyFileWithProgress(sourceFile, destFile, stats)
            stats.copiedFiles++
            logger.info("Successfully copied: ${sourceFile.name}")
        } catch (e: Exception) {
            stats.failedFiles++
            logger.error("Failed to copy file: ${sourceFile.name}", e)
        }
    }

    private suspend fun copyFileWithProgress(
        sourceFile: File,
        destFile: File,
        stats: BackupStats
    ) = withContext(Dispatchers.IO) {
        BufferedInputStream(FileInputStream(sourceFile)).use { input ->
            BufferedOutputStream(FileOutputStream(destFile)).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    stats.totalBytes += bytesRead
                }
                output.flush()
            }
        }
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Backup Service")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getUsbName(usbPath: String): String {
        return usbPath.split("/").lastOrNull() ?: "UnknownUSB"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB Backup Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Monitors and backs up USB devices"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        unregisterReceiver(usbReceiver)
        logger.info("Background service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
