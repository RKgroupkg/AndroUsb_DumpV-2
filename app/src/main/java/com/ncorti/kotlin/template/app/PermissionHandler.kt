package com.ncorti.kotlin.template.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHandler(private val activity: Activity) {
    
    private var permissionCallback: ((Boolean) -> Unit)? = null
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        
        private val STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    fun checkAndRequestPermissions(callback: (Boolean) -> Unit) {
        this.permissionCallback = callback
        
        when {
            // For Android 11 and above
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    callback(true)
                } else {
                    requestAllFilesAccessPermission()
                }
            }
            // For Android 10 and below
            else -> {
                val permissionsToRequest = STORAGE_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()

                when {
                    permissionsToRequest.isEmpty() -> callback(true)
                    shouldShowRequestRationale(permissionsToRequest) -> showPermissionRationale(permissionsToRequest)
                    else -> requestPermissions(permissionsToRequest)
                }
            }
        }
    }

    private fun shouldShowRequestRationale(permissions: Array<String>): Boolean {
        return permissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    private fun showPermissionRationale(permissions: Array<String>) {
        // Show a dialog explaining why we need permissions
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.permissions_required)
            .setMessage(R.string.permission_rationale_message)
            .setPositiveButton(R.string.grant_permissions) { _, _ ->
                requestPermissions(permissions)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                permissionCallback?.invoke(false)
            }
            .show()
    }

    private fun requestAllFilesAccessPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Fallback for devices that don't support the direct permission screen
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            activity.startActivity(intent)
        }
    }

    private fun requestPermissions(permissions: Array<String>) {
        ActivityCompat.requestPermissions(
            activity,
            permissions,
            PERMISSION_REQUEST_CODE
        )
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            permissionCallback?.invoke(allGranted)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            STORAGE_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(activity, permission) == 
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
