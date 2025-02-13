import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object SmartBoardCompatibility {
    fun checkDeviceCompatibility(context: Context): DeviceCompatibility {
        return DeviceCompatibility(
            hasUsbHostSupport = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            hasRequiredAndroidVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
            hasExternalStorage = context.getExternalFilesDir(null) != null
        )
    }
}

data class DeviceCompatibility(
    val hasUsbHostSupport: Boolean,
    val hasRequiredAndroidVersion: Boolean,
    val hasExternalStorage: Boolean
) {
    val isCompatible: Boolean
        get() = hasUsbHostSupport && hasRequiredAndroidVersion && hasExternalStorage

    fun getIncompatibilityReasons(): List<String> {
        val reasons = mutableListOf<String>()
        if (!hasUsbHostSupport) reasons.add("Device does not support USB host mode")
        if (!hasRequiredAndroidVersion) reasons.add("Android version too old (requires 6.0 or higher)")
        if (!hasExternalStorage) reasons.add("No external storage access available")
        return reasons
    }
}
