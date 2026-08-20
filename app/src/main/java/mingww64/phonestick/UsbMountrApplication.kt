package mingww64.phonestick

import android.app.Application
import android.os.Build
import com.topjohnwu.superuser.Shell

class UsbMountrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the persistent app logger first so early events are captured
        AppLogger.init(this)
        AppLogger.i(TAG, "PhoneStick ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) starting")
        AppLogger.i(
            TAG,
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "ABI ${Build.SUPPORTED_ABIS.joinToString(",")}"
        )

        // Apply Material 3 Dynamic Colors
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)

        // Configure TopJohnWu LibSu v6.0.0
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(10)
        )
    }

    companion object {
        private const val TAG = "Application"
    }
}
