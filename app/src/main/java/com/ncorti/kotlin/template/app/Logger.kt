import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class Logger(private val logDir: File) {
    companion object {
        private const val TAG = "USBBackup"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    init {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    private fun getLogFile(): File {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return File(logDir, "backup_log_$date.txt")
    }

    fun info(message: String) {
        log("INFO", message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        log("ERROR", message)
        throwable?.let {
            log("ERROR", "Stack trace: ${it.stackTraceToString()}")
        }
    }

    private fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logMessage = "[$timestamp] $level: $message"
        
        // Log to Android's LogCat
        when (level) {
            "INFO" -> Log.i(TAG, message)
            "ERROR" -> Log.e(TAG, message)
        }

        // Log to file
        try {
            FileWriter(getLogFile(), true).use { writer ->
                writer.append(logMessage).append('\n')
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
}
