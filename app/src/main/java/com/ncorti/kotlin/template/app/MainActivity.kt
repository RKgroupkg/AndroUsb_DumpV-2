package com.ncorti.kotlin.template.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var permissionHandler: PermissionHandler
    private lateinit var rootView: View
    private lateinit var statusText: TextView
    private lateinit var grantPermissionButton: Button
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        rootView = findViewById(R.id.root_view)
        statusText = findViewById(R.id.status_text)
        grantPermissionButton = findViewById(R.id.grant_permission_button)

        // Initialize permission handler
        permissionHandler = PermissionHandler(this)

        // Set up permission button
        grantPermissionButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        // Initial permission check
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        statusText.text = getString(R.string.checking_permissions)
        grantPermissionButton.visibility = View.GONE

        permissionHandler.checkAndRequestPermissions { granted ->
            if (granted) {
                onPermissionsGranted()
            } else {
                if (permissionHandler.shouldShowPermissionRationale()) {
                    showPermissionRationaleDialog()
                } else {
                    showPermissionDeniedMessage()
                }
            }
        }
    }

    private fun onPermissionsGranted() {
        statusText.text = getString(R.string.service_running)
        grantPermissionButton.visibility = View.GONE
        
        if (!serviceStarted) {
            startBackgroundService()
            serviceStarted = true
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_required))
            .setMessage(getString(R.string.permission_rationale_message))
            .setPositiveButton(getString(R.string.grant_permissions)) { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                showPermissionDeniedMessage()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedMessage() {
        statusText.text = getString(R.string.permissions_denied)
        grantPermissionButton.apply {
            visibility = View.VISIBLE
            text = getString(R.string.grant_permissions)
        }

        Snackbar.make(
            rootView,
            getString(R.string.storage_permission_required),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.settings)) {
            openAppSettings()
        }.show()
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        startService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        if (!serviceStarted) {
            checkAndRequestPermissions()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        permissionHandler.onActivityResult(requestCode, resultCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
