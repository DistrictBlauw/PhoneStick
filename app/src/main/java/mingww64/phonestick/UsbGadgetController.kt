package mingww64.phonestick

import android.content.Context
import android.net.Uri
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

object UsbGadgetController {
    private const val TAG = "UsbGadgetController"

    data class MountStatus(
        val isMounted: Boolean = false,
        val currentFile: String = "",
        val isReadOnly: Boolean = false,
        val isCdrom: Boolean = false,
        val lunPath: String = ""
    )

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access: ${e.message}", e)
            false
        }
    }

    fun mountImage(context: Context, pathOrUri: String, readOnly: Boolean, cdrom: Boolean): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "Root access not available")
        }

        var resolvedPath = pathOrUri
        if (pathOrUri.startsWith("content://")) {
            resolvedPath = UriPathResolver.getRealPathFromUri(context, Uri.parse(pathOrUri))
        }

        val file = File(resolvedPath)
        if (!file.exists()) {
            return Pair(false, "Image file path does not exist: $resolvedPath")
        }

        val escapedPath = file.absolutePath.replace("'", "'\\''")
        val roFlag = if (readOnly) "y" else "n"
        val cdromFlag = if (cdrom) "y" else "n"

        // Strategy 1: Check existing Android g1 mass_storage LUN direct write (including /config/usb_gadget)
        val g1LunPaths = listOf(
            "/config/usb_gadget/g1/functions/mass_storage.0/lun.0",
            "/sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0",
            "/sys/class/android_usb/android0/f_mass_storage/lun0",
            "/sys/class/android_usb/android0/f_mass_storage/lun",
            "/sys/devices/virtual/android_usb/android0/f_mass_storage/lun0"
        )

        for (lun in g1LunPaths) {
            val checkLun = Shell.cmd("[ -d $lun ] && echo EXISTS").exec()
            if (checkLun.out.contains("EXISTS")) {
                val directScript = arrayOf(
                    "echo '$roFlag' > $lun/ro 2>/dev/null || true",
                    "echo '$cdromFlag' > $lun/cdrom 2>/dev/null || true",
                    "echo '$escapedPath' > $lun/file",
                    "CHECK_DIRECT=\$(cat $lun/file 2>/dev/null)",
                    "if [ -n \"\$CHECK_DIRECT\" ]; then echo 'DIRECT_SUCCESS'; else echo 'DIRECT_FAILED'; fi"
                )
                val resDirect = Shell.cmd(*directScript).exec()
                if (resDirect.isSuccess && resDirect.out.contains("DIRECT_SUCCESS")) {
                    // Save original USB config & toggle sys.usb.config to force Android init bus re-enumeration pulse
                    saveAndEnableMassStorageConfig(context)
                    return Pair(true, "Successfully mounted via mass storage LUN ($lun)")
                }
            }
        }

        // Strategy 2: Dynamic ConfigFS lookup & swy gadget creation
        val configFsScript = arrayOf(
            "CONFIGFS=\$(mount -t configfs | head -n1 | cut -d' ' -f 3)",
            "if [ -n \"\$CONFIGFS\" ]; then",
            "  echo '' > \$CONFIGFS/usb_gadget/g1/UDC 2>/dev/null || true",
            "  echo '' > \$CONFIGFS/usb_gadget/swy/UDC 2>/dev/null || true",
            "  mkdir -p \$CONFIGFS/usb_gadget/swy",
            "  cd \$CONFIGFS/usb_gadget/swy",
            "  echo 0x1d6b > idVendor 2>/dev/null || true",
            "  echo 0x0104 > idProduct 2>/dev/null || true",
            "  echo 0x0100 > bcdUSB 2>/dev/null || true",
            "  echo 0xEF > bDeviceClass 2>/dev/null || true",
            "  echo 2 > bDeviceSubClass 2>/dev/null || true",
            "  echo 1 > bDeviceProtocol 2>/dev/null || true",
            "  mkdir -p strings/0x409 2>/dev/null || true",
            "  echo 1337 > strings/0x409/serialnumber 2>/dev/null || true",
            "  echo PhoneStick > strings/0x409/manufacturer 2>/dev/null || true",
            "  echo 'PhoneStick Drive' > strings/0x409/product 2>/dev/null || true",
            "  mkdir -p configs/swyconfig.1 2>/dev/null || true",
            "  mkdir -p configs/swyconfig.1/strings/0x409 2>/dev/null || true",
            "  echo 'Mass Storage' > configs/swyconfig.1/strings/0x409/configuration 2>/dev/null || true",
            "  mkdir -p functions/mass_storage.0 2>/dev/null || true",
            "  echo '$roFlag' > functions/mass_storage.0/lun.0/ro 2>/dev/null || true",
            "  echo y > functions/mass_storage.0/lun.0/removable 2>/dev/null || true",
            "  echo '$cdromFlag' > functions/mass_storage.0/lun.0/cdrom 2>/dev/null || true",
            "  echo '$escapedPath' > functions/mass_storage.0/lun.0/file",
            "  ln -s functions/mass_storage.0 configs/swyconfig.1 2>/dev/null || true",
            "  getprop sys.usb.controller > UDC",
            "  CHECK_FILE=\$(cat functions/mass_storage.0/lun.0/file 2>/dev/null)",
            "  if [ -n \"\$CHECK_FILE\" ]; then echo 'CONFIGFS_SUCCESS'; else echo 'CONFIGFS_FAILED'; fi",
            "fi"
        )

        val result1 = Shell.cmd(*configFsScript).exec()
        if (result1.isSuccess && result1.out.contains("CONFIGFS_SUCCESS")) {
            saveAndEnableMassStorageConfig(context)
            return Pair(true, "Successfully mounted via ConfigFS gadget")
        }

        val errReason = if (result1.err.isNotEmpty()) result1.err.joinToString("\n") else "Kernel rejected LUN file binding"
        return Pair(false, "Failed to mount USB gadget: $errReason")
    }

    fun unmountImage(context: Context? = null): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "Root access not available")
        }

        val unmountScript = arrayOf(
            "CONFIGFS=\$(mount -t configfs | head -n1 | cut -d' ' -f 3)",
            "if [ -d \"\$CONFIGFS/usb_gadget/swy\" ]; then",
            "  echo '' > \$CONFIGFS/usb_gadget/swy/UDC 2>/dev/null || true",
            "  cd \$CONFIGFS/usb_gadget/swy 2>/dev/null",
            "  rm -f configs/swyconfig.1/mass_storage.0 2>/dev/null || true",
            "  rmdir configs/swyconfig.1/strings/0x409 2>/dev/null || true",
            "  rmdir configs/swyconfig.1 2>/dev/null || true",
            "  rmdir functions/mass_storage.0 2>/dev/null || true",
            "  rmdir strings/0x409 2>/dev/null || true",
            "  cd .. && rmdir swy 2>/dev/null || true",
            "  echo '' > \$CONFIGFS/usb_gadget/g1/UDC 2>/dev/null || true",
            "  getprop sys.usb.controller > \$CONFIGFS/usb_gadget/g1/UDC 2>/dev/null || true",
            "fi",
            "for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/class/android_usb/android0/f_mass_storage/lun0 /sys/class/android_usb/android0/f_mass_storage/lun; do",
            "  if [ -f \"\$lun/file\" ]; then echo '' > \"\$lun/file\" 2>/dev/null || true; fi",
            "done",
            "echo 'UNMOUNT_SUCCESS'"
        )

        val result = Shell.cmd(*unmountScript).exec()
        if (context != null) {
            restoreOriginalUsbConfig(context)
        } else {
            val defConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val restoredConfig = defConfig.replace("mass_storage,", "").replace(",mass_storage", "").replace("mass_storage", "none")
            Shell.cmd("setprop sys.usb.config $restoredConfig").exec()
        }

        return if (result.isSuccess) {
            Pair(true, "Unmounted successfully")
        } else {
            Pair(false, "Unmount failed: ${result.err.joinToString("\n")}")
        }
    }

    private fun saveAndEnableMassStorageConfig(context: Context) {
        try {
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)

            if (!curConfig.contains("mass_storage")) {
                prefs.edit().putString("orig_usb_config", curConfig).apply()
                val targetConfig = if (curConfig.contains("adb")) "mass_storage,adb" else "mass_storage"
                Shell.cmd("setprop sys.usb.config $targetConfig").exec()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling sys.usb.config: ${e.message}", e)
        }
    }

    private fun restoreOriginalUsbConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)
            val origConfig = prefs.getString("orig_usb_config", "") ?: ""
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: ""

            val restoreTarget = when {
                origConfig.isNotEmpty() -> origConfig
                curConfig.contains("adb") -> "adb"
                else -> "none"
            }
            Log.i(TAG, "Restoring USB config to: $restoreTarget")
            Shell.cmd("setprop sys.usb.config $restoreTarget").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring sys.usb.config: ${e.message}", e)
        }
    }

    fun getMountStatus(): MountStatus {
        if (!isRootAvailable()) return MountStatus()

        val checkScript = arrayOf(
            "CONFIGFS=\$(mount -t configfs | head -n1 | cut -d' ' -f 3)",
            "LUN_FILE=''",
            "LUN_RO=''",
            "LUN_CD=''",
            "LUN_PATH=''",
            "for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 \$CONFIGFS/usb_gadget/swy/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/class/android_usb/android0/f_mass_storage/lun0; do",
            "  if [ -f \"\$lun/file\" ]; then",
            "    CONTENT=\$(cat \"\$lun/file\" 2>/dev/null)",
            "    if [ -n \"\$CONTENT\" ]; then",
            "      LUN_FILE=\"\$CONTENT\"",
            "      LUN_RO=\$(cat \"\$lun/ro\" 2>/dev/null)",
            "      LUN_CD=\$(cat \"\$lun/cdrom\" 2>/dev/null)",
            "      LUN_PATH=\"\$lun\"",
            "      break",
            "    fi",
            "  fi",
            "done",
            "echo \"STATUS|\$LUN_FILE|\$LUN_RO|\$LUN_CD|\$LUN_PATH\""
        )

        val res = Shell.cmd(*checkScript).exec()
        if (res.isSuccess) {
            val line = res.out.firstOrNull { it.startsWith("STATUS|") }
            if (line != null) {
                val parts = line.split("|")
                if (parts.size >= 5 && parts[1].isNotBlank()) {
                    return MountStatus(
                        isMounted = true,
                        currentFile = parts[1],
                        isReadOnly = parts[2] == "1" || parts[2] == "y",
                        isCdrom = parts[3] == "1" || parts[3] == "y",
                        lunPath = parts[4]
                    )
                }
            }
        }
        return MountStatus()
    }
}
