package mingww64.phonestick

import android.app.Application
import com.topjohnwu.superuser.Shell

class UsbMountrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material 3 Dynamic Colors
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)

        // Configure TopJohnWu LibSu v6.0.0
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(10)
        )
    }
}