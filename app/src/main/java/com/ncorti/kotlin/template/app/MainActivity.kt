import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root_view)
        statusText = findViewById(R.id.status_text)
        grantPermissionButton = findViewById(R.id.grant_permission_button)

        permissionHandler = PermissionHandler(this)
        
        grantPermissionButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
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
        statusText.text = "Permissions granted. USB backup service is running."
        grantPermissionButton.visibility = View.GONE
        startBackgroundService()
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "This app needs access to storage to backup files from USB devices. " +
                "Without these permissions, the app cannot function properly."
            )
            .setPositiveButton("Grant Permissions") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Cancel") { _, _ ->
                showPermissionDeniedMessage()
            }
            .show()
    }

    private fun showPermissionDeniedMessage() {
        statusText.text = "Required permissions not granted. App functionality is limited."
        grantPermissionButton.visibility = View.VISIBLE
        
        Snackbar.make(
            rootView,
            "Please grant required permissions in Settings",
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
