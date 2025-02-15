package com.ncorti.kotlin.template.app

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
// Add this import for R
import com.ncorti.kotlin.template.app.R

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
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
                return@setOnClickListener
            }
            startBackgroundService()
        }

        stopButton.setOnClickListener {
            stopBackgroundService()
        }
    }
}

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above
            Environment.isExternalStorageManager()
        } else {
            // For Android 10 and below
            LEGACY_STORAGE_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == 
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }


    private fun hasLegacyStoragePermissions(): Boolean {
        return LEGACY_STORAGE_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                // Some devices might not have this settings page
                val intent = Intent().apply {
                    action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE)
            }
        } else {
            // For Android 10 and below
            ActivityCompat.requestPermissions(
                this,
                LEGACY_STORAGE_PERMISSIONS,
                STORAGE_PERMISSION_REQUEST_CODE
            )
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

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_START_SERVICE
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            checkAndUpdateServiceState()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting service", e)
            Toast.makeText(
                this,
                "Error starting service: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    startBackgroundService()
                } else {
                    showPermissionError()
                }
            }
        }
    }

    private fun showPermissionError() {
        Toast.makeText(
            this,
            "Storage permission is required to Monitor Usb",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
        checkAndUpdateServiceState()
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 123
        private val LEGACY_STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}
