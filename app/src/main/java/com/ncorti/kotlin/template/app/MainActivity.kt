package com.ncorti.kotlin.template.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ncorti.kotlin.template.app.permission.PermissionHandler
import com.ncorti.kotlin.template.app.permission.PermissionState
import com.ncorti.kotlin.template.app.permission.PermissionUI
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var permissionHandler: PermissionHandler
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupPermissionHandler()
        setupButtonListeners()
    }

    private fun initializeViews() {
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        updateButtonStates()
    }

    private fun setupPermissionHandler() {
        permissionHandler = PermissionHandler(this)
        
        // Observe permission state changes
        lifecycleScope.launch {
            permissionHandler.permissionState.collect { state ->
                handlePermissionState(state)
            }
        }
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            if (!isServiceRunning) {
                checkAndRequestPermissions()
            }
        }

        stopButton.setOnClickListener {
            if (isServiceRunning) {
                stopBackgroundService()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (permissionHandler.hasAllPermissions()) {
            startBackgroundService()
        } else {
            permissionHandler.requestPermissions { state ->
                handlePermissionState(state)
            }
        }
    }

    private fun handlePermissionState(state: PermissionState) {
        when (state) {
            PermissionState.GRANTED -> {
                startBackgroundService()
            }
            PermissionState.DENIED -> {
                PermissionUI.showPermissionRationaleDialog(
                    context = this,
                    onRequestPermission = { 
                        permissionHandler.requestPermissions { newState ->
                            handlePermissionState(newState)
                        }
                    },
                    onCancel = { showPermissionRequiredToast() }
                )
            }
            PermissionState.PERMANENTLY_DENIED -> {
                PermissionUI.showPermissionPermanentlyDeniedDialog(
                    context = this,
                    onOpenSettings = { permissionHandler.openSettings() },
                    onCancel = { showPermissionRequiredToast() }
                )
            }
            PermissionState.REQUIRES_MANAGEMENT_ALL -> {
                permissionHandler.requestPermissions { newState ->
                    handlePermissionState(newState)
                }
            }
        }
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_START_SERVICE
            }
            startService(serviceIntent)
            isServiceRunning = true
            updateButtonStates()
            showServiceStartedToast()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            showServiceError(e.message)
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_STOP_SERVICE
            }
            stopService(serviceIntent)
            isServiceRunning = false
            updateButtonStates()
            showServiceStoppedToast()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
            showServiceError(e.message)
        }
    }

    private fun updateButtonStates() {
        startButton.isEnabled = !isServiceRunning
        stopButton.isEnabled = isServiceRunning
    }

    private fun showPermissionRequiredToast() {
        Toast.makeText(
            this,
            R.string.storage_permission_rationale,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showServiceStartedToast() {
        Toast.makeText(
            this,
            R.string.service_started,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showServiceStoppedToast() {
        Toast.makeText(
            this,
            R.string.service_stopped,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showServiceError(error: String?) {
        Toast.makeText(
            this,
            getString(R.string.service_error, error ?: "Unknown error"),
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
