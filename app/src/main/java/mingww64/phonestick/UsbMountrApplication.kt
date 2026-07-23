package mingww64.phonestick

import android.app.Application
import com.topjohnwu.superuser.Shell

class UsbMountrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure TopJohnWu LibSu v6.0.0
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(10)
        )
    }
}