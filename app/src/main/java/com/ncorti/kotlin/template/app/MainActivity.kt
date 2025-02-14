

import com.ncorti.kotlin.template.app.BackgroundService

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var permissionHandler: PermissionHandler

    private val storagePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndUpdateServiceState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupPermissionHandler()
        setupButtonListeners()
        checkAndUpdateServiceState()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun setupPermissionHandler() {
        permissionHandler = PermissionHandler(this)
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                startBackgroundService()
            } else {
                requestRequiredPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopBackgroundService()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasLegacyStoragePermissions()
        }
    }

    private fun hasLegacyStoragePermissions(): Boolean {
        return LEGACY_STORAGE_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess()
        } else {
            permissionHandler.checkAndRequestPermissions { granted ->
                if (granted) {
                    startBackgroundService()
                } else {
                    showPermissionError()
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionsLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback for devices that don't support the direct permission screen
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // Update service management functions:
    private fun startBackgroundService() {
       val serviceIntent = Intent(this, BackgroundService::class.java).apply {
          action = BackgroundService.ACTION_START_SERVICE
     }
    
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          startForegroundService(serviceIntent)
     } else {
          startService(serviceIntent)
     }
    
       checkAndUpdateServiceState()
}

    private fun stopBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_STOP_SERVICE
      }
        stopService(serviceIntent)
        checkAndUpdateServiceState()
}

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == BackgroundService::class.java.name }
    }

    private fun checkAndUpdateServiceState() {
        val isRunning = isServiceRunning()
        updateUIState(isRunning)
    }

    private fun updateUIState(isServiceRunning: Boolean) {
        statusText.text = if (isServiceRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }

        startButton.isEnabled = !isServiceRunning
        stopButton.isEnabled = isServiceRunning
    }

    private fun showPermissionError() {
        Toast.makeText(
            this,
            getString(R.string.storage_permission_required),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
        checkAndUpdateServiceState()
    }

    companion object {
        private val LEGACY_STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}
