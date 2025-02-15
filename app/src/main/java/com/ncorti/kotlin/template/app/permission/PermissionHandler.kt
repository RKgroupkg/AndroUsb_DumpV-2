package com.ncorti.kotlin.template.app.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PermissionHandler(
    private val activity: AppCompatActivity
) : DefaultLifecycleObserver {

    private var permissionCallback: ((PermissionState) -> Unit)? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    private val _permissionState = MutableStateFlow(PermissionState.DENIED)
    val permissionState: StateFlow<PermissionState> = _permissionState

    init {
        activity.lifecycle.addObserver(this)
        setupPermissionLaunchers()
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        checkInitialPermissionState()
    }

    private fun setupPermissionLaunchers() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResult(permissions)
        }

        settingsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            checkInitialPermissionState()
        }
    }

    private fun checkInitialPermissionState() {
        when {
            hasAllPermissions() -> {
                _permissionState.value = PermissionState.GRANTED
                permissionCallback?.invoke(PermissionState.GRANTED)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                _permissionState.value = PermissionState.REQUIRES_MANAGEMENT_ALL
                permissionCallback?.invoke(PermissionState.REQUIRES_MANAGEMENT_ALL)
            }
            shouldShowRationale() -> {
                _permissionState.value = PermissionState.DENIED
                permissionCallback?.invoke(PermissionState.DENIED)
            }
            else -> {
                _permissionState.value = PermissionState.PERMANENTLY_DENIED
                permissionCallback?.invoke(PermissionState.PERMANENTLY_DENIED)
            }
        }
    }

    fun requestPermissions(callback: (PermissionState) -> Unit) {
        permissionCallback = callback

        when {
            hasAllPermissions() -> {
                callback(PermissionState.GRANTED)
                return
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                requestManageAllFilesPermission()
            }
            else -> {
                requestBasicPermissions()
            }
        }
    }

    private fun requestManageAllFilesPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addCategory("android.intent.category.DEFAULT")
                data = Uri.parse("package:${activity.packageName}")
            }
            settingsLauncher.launch(intent)
        } catch (e: Exception) {
            try {
                // Fallback for some devices
                val intent = Intent().apply {
                    action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    data = Uri.parse("package:${activity.packageName}")
                }
                settingsLauncher.launch(intent)
            } catch (e: Exception) {
                // Final fallback - open general settings
                val intent = Intent(Settings.ACTION_SETTINGS)
                settingsLauncher.launch(intent)
            }
        }
    }

    private fun requestBasicPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        when {
            permissions.all { it.value } -> {
                _permissionState.value = PermissionState.GRANTED
                permissionCallback?.invoke(PermissionState.GRANTED)
            }
            shouldShowRationale() -> {
                _permissionState.value = PermissionState.DENIED
                permissionCallback?.invoke(PermissionState.DENIED)
            }
            else -> {
                _permissionState.value = PermissionState.PERMANENTLY_DENIED
                permissionCallback?.invoke(PermissionState.PERMANENTLY_DENIED)
            }
        }
    }
    
    fun hasAllPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(activity, permission) == 
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }
    
    private fun shouldShowRationale(): Boolean {
        return REQUIRED_PERMISSIONS.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
    
    fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        settingsLauncher.launch(intent)
    }
    private fun shouldShowRationale(): Boolean {
        return REQUIRED_PERMISSIONS.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    
    private fun handleError(e: Exception) {
        Log.e(TAG, "Permission error: ${e.message}", e)
        _permissionState.value = PermissionState.PERMANENTLY_DENIED
        permissionCallback?.invoke(PermissionState.PERMANENTLY_DENIED)
      }

    companion object {
        private const val TAG = "PermissionHandler"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}
