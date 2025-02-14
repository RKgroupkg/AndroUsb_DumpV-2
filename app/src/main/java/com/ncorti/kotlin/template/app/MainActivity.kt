package com.ncorti.kotlin.template.app

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
    
    private val storagePermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    
    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        checkStoragePermissionsAndStartService() 
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startBackgroundService()
        } else {
            showPermissionWarning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        setupButtonListeners()
        updateServiceStateUI()
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            checkStoragePermissionsAndStartService()
        }

        stopButton.setOnClickListener {
            stopBackgroundService()
            updateServiceStateUI()
        }
    }

    private fun checkStoragePermissionsAndStartService() {
        when {
            hasAllFilesAccessPermission() -> startBackgroundService()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requestAllFilesAccess()
            else -> requestLegacyStoragePermissions()
        }
    }

    private fun hasAllFilesAccessPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            storagePermissions.all { 
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
            }
        }
    }

    private fun requestAllFilesAccess() {
        try {
            manageAllFilesLauncher.launch(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Navigate to Settings to grant permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun requestLegacyStoragePermissions() {
        when {
            storagePermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } ->
                startBackgroundService()
            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) ->
                showPermissionRationale()
            else -> storagePermissionLauncher.launch(storagePermissions)
        }
    }

    private fun showPermissionRationale() {
        Toast.makeText(this, 
            "Storage access is required to manage USB file transfers", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showPermissionWarning() {
        Toast.makeText(this, 
            "Full storage permissions are required for proper functionality", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startBackgroundService() {
        if (isServiceRunning()) {
            updateServiceStateUI()
            return
        }

        try {
            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_START_SERVICE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            setupServiceMonitor()
            updateServiceStateUI()
        } catch (e: SecurityException) {
            logError("Service start failed", e)
            showPermissionWarning()
        }
    }

    private fun setupServiceMonitor() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val monitorIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_HEARTBEAT
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getService(
            this, 0, monitorIntent, flags
        )

        alarmManager?.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HOUR,
            pendingIntent
        )
    }

    private fun stopBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_STOP_SERVICE
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateServiceStateUI() {
        val isRunning = isServiceRunning()
        statusText.text = if (isRunning) "Service Active" else "Service Inactive"
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == BackgroundService::class.java.name }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStateUI()
    }

    private fun logError(message: String, error: Exception? = null) {
        error?.printStackTrace()
        Toast.makeText(this, "$message: ${error?.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
    }
}
