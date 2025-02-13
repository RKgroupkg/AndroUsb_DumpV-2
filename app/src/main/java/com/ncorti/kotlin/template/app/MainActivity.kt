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

        // Check device compatibility
        checkDeviceCompatibility()

        // Set up permission button
        grantPermissionButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        // Initial permission check
        checkAndRequestPermissions()
    }

    private fun checkDeviceCompatibility() {
        val compatibility = SmartBoardCompatibility.checkDeviceCompatibility(this)
        if (!compatibility.isCompatible) {
            AlertDialog.Builder(this)
                .setTitle("Device Compatibility Check")
                .setMessage(
                    "Some features may not work on this device:\n\n" +
                    compatibility.getIncompatibilityReasons().joinToString("\n")
                )
                .setPositiveButton("Continue Anyway") { _, _ ->
                    checkAndRequestPermissions()
                }
                .setNegativeButton("Exit") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun checkAndRequestPermissions() {
        statusText.text = "Checking permissions..."
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
        statusText.text = "USB Backup Service is active and monitoring for USB devices"
        grantPermissionButton.visibility = View.GONE
        
        if (!serviceStarted) {
            startBackgroundService()
            serviceStarted = true
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "This app needs storage permissions to:\n\n" +
                "• Detect USB devices\n" +
                "• Access files on USB devices\n" +
                "• Create backup copies\n\n" +
                "Without these permissions, the app cannot perform backups."
            )
            .setPositiveButton("Grant Permissions") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Cancel") { _, _ ->
                showPermissionDeniedMessage()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedMessage() {
        statusText.text = "⚠️ Required permissions not granted"
        grantPermissionButton.apply {
            visibility = View.VISIBLE
            text = "Grant Permissions"
        }

        Snackbar.make(
            rootView,
            "Storage permissions are required for USB backup",
            Snackbar.LENGTH_LONG
        ).setAction("Settings") {
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
        startForegroundService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        // Recheck permissions when returning to the app
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
