package mingww64.phonestick

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject

object UsbGadgetController {
    private const val TAG = "UsbGadgetController"

    /** Log a shell execution (exit code + stdout + stderr) at DEBUG/WARN level. */
    private fun logShell(label: String, code: Int, out: List<String>, err: List<String>) {
        AppLogger.shell(TAG, label, code, out, err)
    }

    private const val PREFS = "phonestick"
    private const val KEY_ORIG_USB_CONFIG = "orig_usb_config"
    private const val KEY_DIRECT_MODE = "oplus_direct_mode"
    private const val KEY_USB_CONTROLLER = "usb_controller"
    private const val KEY_GADGET_BACKUP = "gadget_backup"

    /** Name of the dedicated gadget we create to take over the UDC on ColorOS. */
    private const val PS_GADGET_NAME = "pstick"

    data class MountStatus(
        val isMounted: Boolean = false,
        val currentFile: String = "",
        val isReadOnly: Boolean = false,
        val isCdrom: Boolean = false,
        val lunPath: String = ""
    )

    fun isRootAvailable(): Boolean {
        return try {
            val root = Shell.getShell().isRoot
            AppLogger.i(TAG, "Root shell available: $root")
            root
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking root access: ${e.message}", e)
            false
        }
    }

    /**
     * Detect OnePlus / OPPO / realme devices (ColorOS / OxygenOS).
     * These ROMs mount configfs at /config and pre-create the g1 gadget with a
     * mass_storage.0 function instance, but their init never composes
     * sys.usb.config with mass_storage, so the legacy setprop toggle is useless.
     */
    fun isOplusDevice(): Boolean {
        return try {
            val res = Shell.cmd(
                "[ -n \"\$(getprop ro.build.version.oplusrom)\" ] && echo OPLUS",
                "[ -n \"\$(getprop ro.oplus.image.my_bigball.version)\" ] && echo OPLUS"
            ).exec()
            val oplus = res.out.any { it.trim() == "OPLUS" }
            AppLogger.i(TAG, "OPlus device detected: $oplus")
            oplus
        } catch (e: Exception) {
            AppLogger.e(TAG, "OPlus detection failed: ${e.message}", e)
            false
        }
    }

    /**
     * Locate the g1 gadget directory on any configfs mount point.
     * OPlus mounts configfs at /config, standard Android at /sys/kernel/config.
     */
    private fun findG1GadgetDir(): String? {
        val res = Shell.cmd(
            "G=''",
            "for mnt in /config /sys/kernel/config \$(mount -t configfs 2>/dev/null | head -n1 | cut -d' ' -f3); do",
            "  if [ -d \"\$mnt/usb_gadget/g1\" ]; then G=\"\$mnt/usb_gadget/g1\"; break; fi",
            "done",
            "if [ -n \"\$G\" ]; then echo \"\$G\"; fi"
        ).exec()
        val dir = res.out.firstOrNull { it.trim().startsWith("/") }?.trim()
        AppLogger.i(TAG, "Gadget dir lookup: ${dir ?: "not found"}")
        return dir
    }

    private fun shellEscape(s: String): String = s.replace("'", "'\\''")

    // =====================================================================
    // Backup: snapshot the original system USB gadget state before mounting
    // =====================================================================

    /**
     * Capture the full mutable state of the g1 gadget:
     *  - UDC binding
     *  - every function symlink inside every config
     *  - every mass_storage LUN (file / ro / cdrom)
     *  - every mass_storage stall flag
     * Stored as JSON in SharedPreferences; unmount restores from it exactly.
     * Existing snapshots are never overwritten so repeated mounts while a
     * backup is pending always restore to the true original state.
     */
    private fun captureGadgetBackup(context: Context, gadget: String, mode: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_GADGET_BACKUP)) return true // keep the oldest snapshot

        val dump = arrayOf(
            "echo \"UDC|\$(cat '$gadget/UDC' 2>/dev/null)\"",
            "for c in '$gadget'/configs/*; do",
            "  [ -d \"\$c\" ] || continue",
            "  for l in \"\$c\"/*; do",
            "    [ -L \"\$l\" ] && echo \"LINK|\$(basename \"\$c\")|\$(basename \"\$l\")\"",
            "  done",
            "done",
            "for f in '$gadget'/functions/mass_storage.*; do",
            "  [ -d \"\$f\" ] || continue",
            "  for lun in \"\$f\"/lun.*; do",
            "    [ -d \"\$lun\" ] || continue",
            "    echo \"LUN|\${lun#$gadget/}|file=\$(cat \"\$lun/file\" 2>/dev/null)|ro=\$(cat \"\$lun/ro\" 2>/dev/null)|cdrom=\$(cat \"\$lun/cdrom\" 2>/dev/null)\"",
            "  done",
            "  [ -f \"\$f/stall\" ] && echo \"STALL|\${f#$gadget/}|\$(cat \"\$f/stall\" 2>/dev/null)\"",
            "done",
            "true"
        )
        val res = Shell.cmd(*dump).exec()
        if (!res.isSuccess) {
            AppLogger.e(TAG, "Gadget backup dump failed (exit=${res.code}): ${res.err.joinToString(" | ")}")
            return false
        }
        AppLogger.d(TAG, "Gadget backup raw dump:\n" + res.out.joinToString("\n"))

        var udc = ""
        val links = ArrayList<Pair<String, String>>()          // config -> link name
        val luns = ArrayList<Array<String>>()                  // [relPath, file, ro, cdrom]
        val stalls = ArrayList<Pair<String, String>>()         // relPath -> value
        for (raw in res.out) {
            val line = raw.trim()
            when {
                line.startsWith("UDC|") -> udc = line.substring(4)
                line.startsWith("LINK|") -> {
                    val p = line.substring(5).split("|")
                    if (p.size == 2) links.add(Pair(p[0], p[1]))
                }
                line.startsWith("LUN|") -> {
                    val seg = line.substring(4).split("|")
                    if (seg.size == 4) luns.add(arrayOf(
                        seg[0],
                        seg[1].removePrefix("file="),
                        seg[2].removePrefix("ro="),
                        seg[3].removePrefix("cdrom=")
                    ))
                }
                line.startsWith("STALL|") -> {
                    val p = line.substring(6).split("|")
                    if (p.size == 2) stalls.add(Pair(p[0], p[1]))
                }
            }
        }

        val json = JSONObject()
            .put("mode", mode)
            .put("gadget", gadget)
            .put("udc", udc)
        val linksArr = JSONArray()
        for ((conf, link) in links) linksArr.put(JSONObject().put("config", conf).put("link", link))
        json.put("links", linksArr)
        val lunsArr = JSONArray()
        for (l in luns) lunsArr.put(JSONObject()
            .put("path", l[0]).put("file", l[1]).put("ro", l[2]).put("cdrom", l[3]))
        json.put("luns", lunsArr)
        val stallArr = JSONArray()
        for ((path, value) in stalls) stallArr.put(JSONObject().put("path", path).put("value", value))
        json.put("stalls", stallArr)

        prefs.edit().putString(KEY_GADGET_BACKUP, json.toString()).apply()
        AppLogger.i(TAG, "Gadget backup captured (mode=$mode, udc=$udc, links=${links.size}, luns=${luns.size}, stalls=${stalls.size})")
        return true
    }

    /**
     * Lightweight backup for the legacy direct-LUN strategy: record the
     * original file/ro/cdrom of one sysfs/configfs LUN directory.
     */
    private fun captureLunBackup(context: Context, lunDir: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_GADGET_BACKUP)) return

        val res = Shell.cmd(
            "F=\$(cat '$lunDir/file' 2>/dev/null)",
            "R=\$(cat '$lunDir/ro' 2>/dev/null)",
            "C=\$(cat '$lunDir/cdrom' 2>/dev/null)",
            "echo \"LUN0|file=\$F|ro=\$R|cdrom=\$C\""
        ).exec()
        val line = res.out.firstOrNull { it.startsWith("LUN0|") } ?: return
        val seg = line.substring(5).split("|")
        if (seg.size != 3) return

        val json = JSONObject()
            .put("mode", "g1")
            .put("gadget", "")
            .put("udc", "")
            .put("links", JSONArray())
            .put("stalls", JSONArray())
            .put("luns", JSONArray().put(JSONObject()
                .put("path", lunDir)
                .put("file", seg[0].removePrefix("file="))
                .put("ro", seg[1].removePrefix("ro="))
                .put("cdrom", seg[2].removePrefix("cdrom="))))
        prefs.edit().putString(KEY_GADGET_BACKUP, json.toString()).apply()
        AppLogger.i(TAG, "LUN backup captured for $lunDir (file=${seg[0].removePrefix("file=")}, ro=${seg[1].removePrefix("ro=")}, cdrom=${seg[2].removePrefix("cdrom=")})")
    }

    // =====================================================================
    // Restore: return the gadget to its pre-mount state
    // =====================================================================

    private fun clearBackupKeys(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_GADGET_BACKUP)
            .putBoolean(KEY_DIRECT_MODE, false)
            .remove(KEY_USB_CONTROLLER)
            .apply()
    }

    /**
     * Restore an OPlus full-gadget snapshot:
     * detach UDC -> strip mass_storage links -> recreate snapshot links ->
     * restore LUN values + stall -> rebind the saved UDC -> verify.
     */
    private fun restoreOplusBackup(context: Context, json: JSONObject): Pair<Boolean, String> {
        val gadget = json.optString("gadget")
        val udc = json.optString("udc")
        if (gadget.isBlank()) return Pair(false, context.getString(R.string.err_backup_no_gadget))
        AppLogger.i(TAG, "Restoring OPlus backup: gadget=$gadget, udc=$udc, links=${json.optJSONArray("links")?.length() ?: 0}, luns=${json.optJSONArray("luns")?.length() ?: 0}")

        val script = ArrayList<String>()
        script.add("echo '' > '$gadget/UDC' 2>/dev/null || true")
        script.add("sleep 1")
        // strip every mass_storage link we may have added
        script.add("for l in '$gadget'/configs/*/mass_storage.*; do [ -L \"\$l\" ] && rm -f \"\$l\" 2>/dev/null; done; true")

        // recreate mass_storage links that existed in the snapshot
        val links = json.optJSONArray("links") ?: JSONArray()
        for (i in 0 until links.length()) {
            val o = links.optJSONObject(i) ?: continue
            val conf = o.optString("config")
            val link = o.optString("link")
            if (conf.isBlank() || !link.startsWith("mass_storage")) continue
            val target = "$gadget/configs/$conf/$link"
            script.add("if [ ! -e '$target' ]; then ln -s '$gadget/functions/$link' '$target' 2>/dev/null || true; fi")
        }

        // restore LUN contents
        val luns = json.optJSONArray("luns") ?: JSONArray()
        val lunChecks = ArrayList<Pair<String, String>>() // dir -> expected file
        for (i in 0 until luns.length()) {
            val o = luns.optJSONObject(i) ?: continue
            val dir = "$gadget/${o.optString("path")}"
            val file = o.optString("file")
            val ro = o.optString("ro")
            val cdrom = o.optString("cdrom")
            lunChecks.add(Pair(dir, file))
            script.add("echo '${shellEscape(file)}' > '$dir/file' 2>/dev/null || true")
            if (ro.isNotBlank()) script.add("echo '$ro' > '$dir/ro' 2>/dev/null || true")
            if (cdrom.isNotBlank()) script.add("echo '$cdrom' > '$dir/cdrom' 2>/dev/null || true")
        }

        // restore stall flags
        val stalls = json.optJSONArray("stalls") ?: JSONArray()
        for (i in 0 until stalls.length()) {
            val o = stalls.optJSONObject(i) ?: continue
            val path = o.optString("path")
            val value = o.optString("value")
            if (path.isBlank() || value.isBlank()) continue
            script.add("echo '$value' > '$gadget/$path/stall' 2>/dev/null || true")
        }

        // rebind the saved UDC
        if (udc.isNotBlank()) script.add("echo '${shellEscape(udc)}' > '$gadget/UDC' 2>/dev/null || true")
        script.add("sleep 1")

        // verification pass (the file node must exist AND match the snapshot)
        script.add("RC=0")
        for ((dir, expected) in lunChecks) {
            script.add("if [ ! -f '$dir/file' ] || [ \"\$(cat '$dir/file' 2>/dev/null)\" != '${shellEscape(expected)}' ]; then RC=1; fi")
        }
        if (udc.isNotBlank()) script.add("if [ -z \"\$(cat '$gadget/UDC' 2>/dev/null)\" ]; then RC=1; fi")
        script.add("if [ \"\$RC\" = 0 ]; then echo RESTORE_OK; else echo RESTORE_FAIL; fi")

        val res = Shell.cmd(*script.toTypedArray()).exec()
        logShell("OPlus restore script", res.code, res.out, res.err)
        return if (res.out.any { it.trim() == "RESTORE_OK" }) {
            clearBackupKeys(context)
            AppLogger.i(TAG, "OPlus backup restored successfully (UDC=$udc)")
            Pair(true, context.getString(R.string.msg_restored_oplus, udc))
        } else {
            val failLine = res.out.firstOrNull { it.contains("RESTORE_FAIL") } ?: ""
            val err = res.err.joinToString("\n").ifEmpty { failLine }
            AppLogger.e(TAG, "OPlus restore failed: $err (backup kept for retry)")
            Pair(false, context.getString(R.string.err_restore_failed, err))
        }
    }

    /**
     * Restore a legacy g1 LUN snapshot: put the recorded file/ro/cdrom values
     * back into the recorded LUN directory, then restore sys.usb.config.
     */
    private fun restoreG1Backup(context: Context, json: JSONObject): Pair<Boolean, String> {
        val luns = json.optJSONArray("luns") ?: JSONArray()
        AppLogger.i(TAG, "Restoring g1 LUN backup (${luns.length()} LUN entries)")
        val script = ArrayList<String>()
        val checks = ArrayList<Pair<String, String>>()
        for (i in 0 until luns.length()) {
            val o = luns.optJSONObject(i) ?: continue
            val dir = o.optString("path")
            if (dir.isBlank()) continue
            val file = o.optString("file")
            val ro = o.optString("ro")
            val cdrom = o.optString("cdrom")
            checks.add(Pair(dir, file))
            script.add("echo '${shellEscape(file)}' > '$dir/file' 2>/dev/null || true")
            if (ro.isNotBlank()) script.add("echo '$ro' > '$dir/ro' 2>/dev/null || true")
            if (cdrom.isNotBlank()) script.add("echo '$cdrom' > '$dir/cdrom' 2>/dev/null || true")
        }
        script.add("RC=0")
        for ((dir, expected) in checks) {
            script.add("if [ ! -f '$dir/file' ] || [ \"\$(cat '$dir/file' 2>/dev/null)\" != '${shellEscape(expected)}' ]; then RC=1; fi")
        }
        script.add("if [ \"\$RC\" = 0 ]; then echo RESTORE_OK; else echo RESTORE_FAIL; fi")

        val res = Shell.cmd(*script.toTypedArray()).exec()
        logShell("g1 LUN restore script", res.code, res.out, res.err)
        val ok = res.out.any { it.trim() == "RESTORE_OK" }
        if (ok) clearBackupKeys(context)
        restoreOriginalUsbConfig(context)
        return if (ok) {
            AppLogger.i(TAG, "g1 LUN backup restored successfully")
            Pair(true, context.getString(R.string.msg_restored_g1))
        } else {
            AppLogger.e(TAG, "g1 LUN restore failed: ${res.err.joinToString("\n")} (backup kept for retry)")
            Pair(false, context.getString(R.string.msg_g1_restore_failed, res.err.joinToString("\n")))
        }
    }

    // =====================================================================
    // Mount
    // =====================================================================

    fun mountImage(context: Context, pathOrUri: String, readOnly: Boolean, cdrom: Boolean): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, context.getString(R.string.err_no_root))
        }

        var resolvedPath = pathOrUri
        if (pathOrUri.startsWith("content://")) {
            resolvedPath = UriPathResolver.getRealPathFromUri(context, Uri.parse(pathOrUri))
        }

        val escapedPath = resolvedPath.replace("'", "'\\''")
        val existsCmd = Shell.cmd("if [ -f '$escapedPath' ]; then echo EXISTS; fi").exec()
        if (!existsCmd.out.contains("EXISTS")) {
            return Pair(false, context.getString(R.string.err_file_missing, resolvedPath))
        }

        val roFlag = if (readOnly) "y" else "n"
        val cdromFlag = if (cdrom) "y" else "n"
        AppLogger.i(TAG, "mountImage: path=$resolvedPath, readOnly=$readOnly, cdrom=$cdrom")

        // Strategy 0 (OPlus): dedicated gadget takes over the UDC so the ColorOS
        // USB HAL cannot reset the composition back to MTP; classic g1
        // composition remains as a fallback for restricted kernels.
        if (isOplusDevice()) {
            AppLogger.i(TAG, "Strategy: OPlus independent gadget (pstick)")
            val indep = mountViaIndependentGadget(context, escapedPath, roFlag, cdromFlag)
            if (indep.first) return indep
            AppLogger.w(TAG, "Independent gadget failed (${indep.second}); falling back to g1 composition")
            return mountViaOplusConfigfs(context, escapedPath, roFlag, cdromFlag)
        }

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
                // Backup the original LUN state before touching it
                captureLunBackup(context, "$lun")
                val directScript = arrayOf(
                    "echo '$roFlag' > $lun/ro 2>/dev/null || true",
                    "echo '$cdromFlag' > $lun/cdrom 2>/dev/null || true",
                    "echo '$escapedPath' > $lun/file",
                    "CHECK_DIRECT=\$(cat $lun/file 2>/dev/null)",
                    "if [ -n \"\$CHECK_DIRECT\" ]; then echo 'DIRECT_SUCCESS'; else echo 'DIRECT_FAILED'; fi"
                )
                val resDirect = Shell.cmd(*directScript).exec()
                logShell("Strategy 1 direct LUN write ($lun)", resDirect.code, resDirect.out, resDirect.err)
                if (resDirect.isSuccess && resDirect.out.contains("DIRECT_SUCCESS")) {
                    // Save original USB config & toggle sys.usb.config to force Android init bus re-enumeration pulse
                    saveAndEnableMassStorageConfig(context)
                    return Pair(true, context.getString(R.string.msg_mounted_lun, lun))
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
        logShell("Strategy 2 swy ConfigFS gadget", result1.code, result1.out, result1.err)
        if (result1.isSuccess && result1.out.contains("CONFIGFS_SUCCESS")) {
            saveAndEnableMassStorageConfig(context)
            return Pair(true, context.getString(R.string.msg_mounted_configfs))
        }

        val errReason = result1.err.joinToString("\n").ifEmpty {
            result1.out.joinToString("\n").ifEmpty { "Kernel rejected LUN file binding" }
        }
        AppLogger.e(TAG, "All mount strategies failed (exit code ${result1.code}): $errReason")
        return Pair(false, context.getString(R.string.msg_mount_failed, result1.code, errReason))
    }

    // =====================================================================
    // Independent gadget strategy (fix for ColorOS resetting g1 to MTP)
    // =====================================================================

    /**
     * The ColorOS USB HAL owns the pre-created g1 gadget and rewrites its
     * composition whenever it deviates from sys.usb.config: a few seconds
     * after we add mass_storage to g1 the HAL strips the link and the PC
     * falls back to MTP. Instead we create our own gadget (pstick) which the
     * HAL does not know about, let it take over the UDC, and never touch g1
     * except for detaching it. g1 keeps its full configuration and is simply
     * re-bound on unmount, returning the phone to its original state.
     */
    private fun mountViaIndependentGadget(context: Context, escapedPath: String, roFlag: String, cdromFlag: String): Pair<Boolean, String> {
        val g1 = findG1GadgetDir()
            ?: return Pair(false, context.getString(R.string.err_gadget_not_found))
        val ps = "${g1.substringBeforeLast("/")}/$PS_GADGET_NAME"

        val ctrl = resolveUsbController()
        if (ctrl.isBlank()) {
            return Pair(false, context.getString(R.string.err_no_udc))
        }

        // Snapshot only what we change: g1's UDC binding (g1 itself is never modified)
        val curUdc = Shell.cmd("cat '$g1/UDC' 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
        val udcBackup = curUdc.ifBlank { ctrl }
        storeIndependentBackup(context, g1, udcBackup)

        val script = arrayOf(
            // teardown leftover instance from a previous crashed run (idempotent)
            "if [ -d '$ps' ]; then",
            "  echo '' > '$ps/UDC' 2>/dev/null || true",
            "  echo '' > '$ps/functions/mass_storage.0/lun.0/file' 2>/dev/null || true",
            "  rm -f '$ps/configs/c.1/mass_storage.0' 2>/dev/null || true",
            "  rmdir '$ps/configs/c.1/strings/0x409' '$ps/configs/c.1' '$ps/functions/mass_storage.0' '$ps/strings/0x409' '$ps' 2>/dev/null || true",
            "fi",
            // create the gadget (kernel instantiates default groups + attributes)
            "mkdir '$ps' 2>/dev/null || true",
            "mkdir '$ps/strings/0x409' 2>/dev/null || true",
            "mkdir '$ps/configs/c.1' 2>/dev/null || true",
            "mkdir '$ps/configs/c.1/strings/0x409' 2>/dev/null || true",
            "mkdir '$ps/functions/mass_storage.0' 2>/dev/null || true",
            "RC=0",
            "[ -d '$ps/functions/mass_storage.0/lun.0' ] || RC=1",
            "[ -d '$ps/configs/c.1' ] || RC=1",
            "if [ \"\$RC\" = 0 ]; then",
            "  echo 0x0525 > '$ps/idVendor' 2>/dev/null || true",
            "  echo 0xa4a5 > '$ps/idProduct' 2>/dev/null || true",
            "  echo 0x0100 > '$ps/bcdDevice' 2>/dev/null || true",
            "  echo PhoneStick > '$ps/strings/0x409/manufacturer' 2>/dev/null || true",
            "  echo 'PhoneStick Drive' > '$ps/strings/0x409/product' 2>/dev/null || true",
            "  echo phonestick > '$ps/strings/0x409/serialnumber' 2>/dev/null || true",
            "  echo 500 > '$ps/configs/c.1/MaxPower' 2>/dev/null || true",
            "  echo 'Mass Storage' > '$ps/configs/c.1/strings/0x409/configuration' 2>/dev/null || true",
            "  echo 1 > '$ps/functions/mass_storage.0/stall' 2>/dev/null || true",
            "  echo y > '$ps/functions/mass_storage.0/lun.0/removable' 2>/dev/null || true",
            "  echo '$roFlag' > '$ps/functions/mass_storage.0/lun.0/ro' 2>/dev/null || true",
            "  echo '$cdromFlag' > '$ps/functions/mass_storage.0/lun.0/cdrom' 2>/dev/null || true",
            "  echo '$escapedPath' > '$ps/functions/mass_storage.0/lun.0/file' 2>/dev/null || true",
            // SELinux fallback: some OPlus builds deny configfs writes even to root
            "  if [ -z \"\$(cat '$ps/functions/mass_storage.0/lun.0/file' 2>/dev/null)\" ] && [ \"\$(getenforce 2>/dev/null)\" = Enforcing ]; then",
            "    setenforce 0 2>/dev/null || true",
            "    echo '$escapedPath' > '$ps/functions/mass_storage.0/lun.0/file' 2>/dev/null || true",
            "    setenforce 1 2>/dev/null || true",
            "  fi",
            "  ln -s '$ps/functions/mass_storage.0' '$ps/configs/c.1/mass_storage.0' 2>/dev/null || true",
            // detach g1 and hand the UDC to pstick (retry: the HAL may race us)
            "  TRY=0",
            "  while [ \"\$RC\" = 0 ] && [ \$TRY -lt 5 ]; do",
            "    echo '' > '$g1/UDC' 2>/dev/null || true",
            "    if echo '$ctrl' > '$ps/UDC' 2>/dev/null; then",
            "      sleep 1",
            "      [ \"\$(cat '$ps/UDC' 2>/dev/null)\" = '$ctrl' ] && break",
            "    else",
            "      sleep 1",
            "    fi",
            "    TRY=\$((TRY+1))",
            "  done",
            "fi",
            "sleep 1",
            "LUNVAL=\$(cat '$ps/functions/mass_storage.0/lun.0/file' 2>/dev/null)",
            "UDCVAL=\$(cat '$ps/UDC' 2>/dev/null)",
            "if [ -n \"\$LUNVAL\" ] && [ \"\$UDCVAL\" = '$ctrl' ]; then",
            "  echo 'OK|$ctrl'",
            "else",
            // give the UDC back to g1 so the system is not left without USB
            "  echo '$udcBackup' > '$g1/UDC' 2>/dev/null || true",
            "  echo \"FAIL|\$UDCVAL|\$LUNVAL\"",
            "fi"
        )

        val res = Shell.cmd(*script).exec()
        logShell("Independent gadget mount script", res.code, res.out, res.err)
        val okLine = res.out.firstOrNull { it.startsWith("OK|") }
        if (okLine != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DIRECT_MODE, true)
                .putString(KEY_USB_CONTROLLER, ctrl)
                .remove(KEY_ORIG_USB_CONFIG) // this mode never touches sys.usb.config
                .apply()
            AppLogger.i(TAG, "Independent gadget mounted: pstick holds UDC $ctrl (g1 detached, snapshot kept)")
            return Pair(true, context.getString(R.string.msg_mounted_pstick, ctrl))
        }

        // Failure: drop our snapshot so the g1-composition fallback can record its own
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_GADGET_BACKUP).apply()
        val failLine = res.out.firstOrNull { it.startsWith("FAIL|") } ?: "no result"
        val detail = (listOf(failLine) + res.err).filter { it.isNotBlank() }.joinToString("\n")
        AppLogger.e(TAG, "Independent gadget mount failed:\n$detail")
        return Pair(false, context.getString(R.string.msg_pstick_mount_failed, detail))
    }

    /** Resolve the UDC name: sys.usb.controller -> vendor.usb.controller -> /sys/class/udc. */
    private fun resolveUsbController(): String {
        val res = Shell.cmd(
            "CTRL=\$(getprop sys.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(getprop vendor.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(ls /sys/class/udc 2>/dev/null | head -n1)",
            "[ -n \"\$CTRL\" ] && echo \"\$CTRL\""
        ).exec()
        val ctrl = res.out.firstOrNull()?.trim() ?: ""
        AppLogger.i(TAG, "UDC resolved: ${ctrl.ifBlank { "none" }}")
        return ctrl
    }

    /**
     * Snapshot for the independent strategy: g1's UDC binding plus the
     * sys.usb.config / sys.usb.state property pair. The properties matter on
     * unmount: the ColorOS HAL leaves g1's composition EMPTY after we detach
     * it for minutes, so merely rebinding the UDC would enumerate a device
     * with no interfaces ("charging only" on the PC). Cycling sys.usb.config
     * through none -> original makes init rebuild the composition.
     */
    private fun storeIndependentBackup(context: Context, g1: String, udc: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_GADGET_BACKUP)) {
            AppLogger.w(TAG, "A gadget backup already exists; keeping the older snapshot")
            return
        }
        val propRes = Shell.cmd(
            "echo \"USB_CONFIG|\$(getprop sys.usb.config)\"",
            "echo \"USB_STATE|\$(getprop sys.usb.state)\""
        ).exec()
        var usbConfig = ""
        var usbState = ""
        for (raw in propRes.out) {
            val line = raw.trim()
            when {
                line.startsWith("USB_CONFIG|") -> usbConfig = line.substring(11)
                line.startsWith("USB_STATE|") -> usbState = line.substring(10)
            }
        }
        val json = JSONObject()
            .put("mode", "independent")
            .put("gadget", g1)
            .put("udc", udc)
            .put("usbConfig", usbConfig)
            .put("usbState", usbState)
            .put("links", JSONArray())
            .put("luns", JSONArray())
            .put("stalls", JSONArray())
        prefs.edit().putString(KEY_GADGET_BACKUP, json.toString()).apply()
        AppLogger.i(TAG, "Independent backup stored (gadget=$g1, udc=$udc, usbConfig='$usbConfig', usbState='$usbState')")
    }

    /** Remove the pstick gadget entirely (idempotent). Returns true when gone. */
    private fun teardownPstick(g1: String): Boolean {
        val ps = "${g1.substringBeforeLast("/")}/$PS_GADGET_NAME"
        val res = Shell.cmd(
            "if [ -d '$ps' ]; then",
            "  echo '' > '$ps/UDC' 2>/dev/null || true",
            "  sleep 1",
            "  rm -f '$ps/configs/c.1/mass_storage.0' 2>/dev/null || true",
            "  rmdir '$ps/configs/c.1/strings/0x409' '$ps/configs/c.1' '$ps/functions/mass_storage.0' '$ps/strings/0x409' '$ps' 2>/dev/null || true",
            "fi",
            "if [ -d '$ps' ]; then echo STILL_THERE; else echo GONE; fi"
        ).exec()
        logShell("pstick teardown", res.code, res.out, res.err)
        val gone = res.out.any { it.trim() == "GONE" }
        if (!gone) AppLogger.w(TAG, "pstick gadget could not be removed completely")
        return gone
    }

    /**
     * Build the sys.usb.config recompose cycle: setting 'none' runs init's
     * teardown trigger (unbinds the gadget), restoring the original value
     * runs the compose trigger which recreates every function symlink, binds
     * the UDC and publishes sys.usb.state again. This is the ONLY reliable
     * way to bring g1 back after the ColorOS HAL has stripped its
     * composition - and it resyncs the framework USB state machine so MTP /
     * tethering choices reappear on the phone.
     */
    private fun buildRecomposeLines(g1: String, usbConfig: String): List<String> {
        val lines = ArrayList<String>()
        lines.add("USBCFG='$usbConfig'")
        lines.add("[ -z \"\$USBCFG\" ] && USBCFG=\$(getprop sys.usb.config)")
        lines.add("if [ -n \"\$USBCFG\" ] && [ \"\$USBCFG\" != none ]; then")
        lines.add("  setprop sys.usb.config none 2>/dev/null || true")
        lines.add("  sleep 1")
        lines.add("  setprop sys.usb.config \"\$USBCFG\" 2>/dev/null || true")
        // init composes asynchronously: poll for the rebind (max ~10s)
        lines.add("  I=0")
        lines.add("  while [ \$I -lt 10 ]; do")
        lines.add("    sleep 1")
        lines.add("    [ -n \"\$(cat '$g1/UDC' 2>/dev/null)\" ] && break")
        lines.add("    I=\$((I+1))")
        lines.add("  done")
        lines.add("fi")
        return lines
    }

    /** Restore the independent-gadget snapshot: remove pstick, recompose g1. */
    private fun restoreIndependentBackup(context: Context, json: JSONObject): Pair<Boolean, String> {
        val g1 = json.optString("gadget")
        val udc = json.optString("udc")
        val usbConfig = json.optString("usbConfig")
        if (g1.isBlank()) return Pair(false, context.getString(R.string.err_backup_no_gadget))
        AppLogger.i(TAG, "Restoring independent-gadget backup: g1=$g1, udc=$udc, usbConfig='$usbConfig'")

        val gone = teardownPstick(g1)

        // Detach g1 first so init's recomposition starts clean, then cycle
        // sys.usb.config to force the compose trigger (rebuilds links + binds
        // UDC + resyncs the framework). Manual rebind stays as a fallback.
        val script = ArrayList<String>()
        script.add("echo '' > '$g1/UDC' 2>/dev/null || true")
        script.addAll(buildRecomposeLines(g1, usbConfig))
        script.add("if [ -z \"\$(cat '$g1/UDC' 2>/dev/null)\" ]; then")
        if (udc.isNotBlank()) script.add("  echo '${shellEscape(udc)}' > '$g1/UDC' 2>/dev/null || true")
        script.add("  sleep 1")
        script.add("fi")
        script.add("sleep 1")
        script.add("echo \"UDC_NOW|\$(cat '$g1/UDC' 2>/dev/null)\"")
        script.add("echo \"USBCFG_NOW|\$(getprop sys.usb.config)\"")

        val res = Shell.cmd(*script.toTypedArray()).exec()
        logShell("g1 recompose (sys.usb.config cycle)", res.code, res.out, res.err)
        val bound = if (udc.isNotBlank()) {
            res.out.firstOrNull { it.startsWith("UDC_NOW|") }?.substring(8)?.trim() == udc
        } else {
            res.out.firstOrNull { it.startsWith("UDC_NOW|") }?.substring(8)?.isNotBlank() == true
        }

        return if (gone && bound) {
            clearBackupKeys(context)
            AppLogger.i(TAG, "Independent unmount complete (g1 recomposed, UDC=${res.out.firstOrNull { it.startsWith("UDC_NOW|") }?.substring(8)?.trim()})")
            Pair(true, context.getString(R.string.msg_unmounted_pstick))
        } else {
            AppLogger.e(TAG, "Independent unmount failed (gone=$gone, bound=$bound)")
            Pair(false, context.getString(R.string.msg_pstick_unmount_failed, "gone=$gone bound=$bound"))
        }
    }

    /**
     * OPlus (OnePlus/OPPO/realme) direct configfs mount:
     *  0. snapshot the original gadget state for later restore
     *  1. detach g1 from the UDC so config links and LUN attributes are writable
     *  2. fill mass_storage.0/lun.0 (file / ro / cdrom / removable / stall)
     *  3. symlink functions/mass_storage.0 into the active config (configs/b.1)
     *  4. re-attach the UDC (e.g. a600000.dwc3) so the host re-enumerates
     * ADB and MTP keep working because the original composition is preserved.
     */
    private fun mountViaOplusConfigfs(context: Context, escapedPath: String, roFlag: String, cdromFlag: String): Pair<Boolean, String> {
        val gadget = findG1GadgetDir()
            ?: return Pair(false, context.getString(R.string.err_gadget_not_found))

        val funcDir = "$gadget/functions/mass_storage.0"
        val hasFunc = Shell.cmd("[ -d '$funcDir/lun.0' ] && echo YES").exec().out.contains("YES")
        if (!hasFunc) {
            return Pair(false, context.getString(R.string.err_ms_func_missing, funcDir))
        }

        // Backup original system configuration before any modification
        val backedUp = captureGadgetBackup(context, gadget, "oplus")
        if (!backedUp) {
            AppLogger.w(TAG, "Failed to snapshot gadget state; mount continues without backup")
        }

        val script = arrayOf(
            // Pick the active config directory (Qualcomm/OPlus convention: configs/b.1)
            "CONF=''",
            "for c in '$gadget'/configs/*; do if [ -d \"\$c\" ]; then CONF=\"\$c\"; break; fi; done",
            // Resolve UDC name: sys.usb.controller -> vendor.usb.controller -> /sys/class/udc
            "CTRL=\$(getprop sys.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(getprop vendor.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(ls /sys/class/udc 2>/dev/null | head -n1)",
            // 1. detach gadget from UDC
            "echo '' > '$gadget/UDC' 2>/dev/null || true",
            "sleep 1",
            // 2. configure the LUN
            "echo y > '$funcDir/lun.0/removable' 2>/dev/null || true",
            "echo '$roFlag' > '$funcDir/lun.0/ro' 2>/dev/null || true",
            "echo '$cdromFlag' > '$funcDir/lun.0/cdrom' 2>/dev/null || true",
            "echo 0 > '$funcDir/stall' 2>/dev/null || true",
            "echo '$escapedPath' > '$funcDir/lun.0/file' 2>/dev/null || true",
            // SELinux fallback: some OPlus builds deny configfs writes even to root
            "if [ -z \"\$(cat '$funcDir/lun.0/file' 2>/dev/null)\" ] && [ \"\$(getenforce 2>/dev/null)\" = Enforcing ]; then",
            "  setenforce 0 2>/dev/null || true",
            "  echo '$escapedPath' > '$funcDir/lun.0/file' 2>/dev/null || true",
            "  setenforce 1 2>/dev/null || true",
            "fi",
            // 3. bind mass_storage.0 into the active config (skip if already linked)
            "if [ -n \"\$CONF\" ] && [ ! -e \"\$CONF/mass_storage.0\" ]; then",
            "  ln -s '$funcDir' \"\$CONF/mass_storage.0\" 2>/dev/null || true",
            "fi",
            // 4. re-attach the UDC so the host re-enumerates
            "[ -n \"\$CTRL\" ] && echo \"\$CTRL\" > '$gadget/UDC' 2>/dev/null || true",
            "sleep 1",
            // Verify: LUN set + function linked + gadget bound again
            "LUNVAL=\$(cat '$funcDir/lun.0/file' 2>/dev/null)",
            "UDCVAL=\$(cat '$gadget/UDC' 2>/dev/null)",
            "if [ -n \"\$LUNVAL\" ] && [ -n \"\$CONF\" ] && [ -e \"\$CONF/mass_storage.0\" ] && [ -n \"\$UDCVAL\" ]; then",
            "  echo \"OK|\$CONF|\$CTRL\"",
            "else",
            "  echo \"FAIL|\$CONF|\$CTRL|\$LUNVAL|\$UDCVAL\"",
            "fi"
        )

        val res = Shell.cmd(*script).exec()
        logShell("OPlus mount script", res.code, res.out, res.err)
        val okLine = res.out.firstOrNull { it.startsWith("OK|") }
        if (okLine != null) {
            val parts = okLine.split("|")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_DIRECT_MODE, true)
                .putString(KEY_USB_CONTROLLER, parts.getOrNull(2) ?: "")
                .remove(KEY_ORIG_USB_CONFIG) // direct mode never touches sys.usb.config
                .apply()
            val confName = (parts.getOrNull(1) ?: "?").substringAfterLast('/')
            AppLogger.i(TAG, "OPlus mount succeeded: config=$confName, UDC=${parts.getOrNull(2) ?: "?"}")
            return Pair(true, context.getString(R.string.msg_mounted_oplus, confName, parts.getOrNull(2) ?: "?"))
        }

        val failLine = res.out.firstOrNull { it.startsWith("FAIL|") } ?: "no result"
        val audit = try {
            Shell.cmd("dmesg 2>/dev/null | grep -i 'avc.*denied' | tail -n 2").exec().out
        } catch (e: Exception) {
            emptyList()
        }
        val detail = (listOf(failLine) + res.err + audit).filter { it.isNotBlank() }.joinToString("\n")
        AppLogger.e(TAG, "OPlus mount failed:\n$detail")
        return Pair(false, context.getString(R.string.msg_oplus_mount_failed, detail))
    }

    // =====================================================================
    // Unmount
    // =====================================================================

    fun unmountImage(context: Context? = null): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, context?.getString(R.string.err_no_root) ?: "Root access not available")
        }

        // Preferred path: restore from the snapshot taken before mounting
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_GADGET_BACKUP, null)
            if (!raw.isNullOrEmpty()) {
                val json = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    null
                }
                if (json != null) {
                    // Always remove a lingering pstick gadget first (crashed sessions)
                    findG1GadgetDir()?.let { teardownPstick(it) }
                    when (json.optString("mode")) {
                        "independent" -> {
                            AppLogger.i(TAG, "Unmount path: restore from independent-gadget snapshot")
                            return restoreIndependentBackup(context, json)
                        }
                        "oplus" -> {
                            AppLogger.i(TAG, "Unmount path: restore from OPlus snapshot")
                            return restoreOplusBackup(context, json)
                        }
                        "g1" -> {
                            AppLogger.i(TAG, "Unmount path: restore from g1 LUN snapshot")
                            return restoreG1Backup(context, json)
                        }
                        else -> prefs.edit().remove(KEY_GADGET_BACKUP).apply()
                    }
                } else {
                    AppLogger.w(TAG, "Corrupt backup JSON found, discarding")
                    prefs.edit().remove(KEY_GADGET_BACKUP).apply()
                }
            } else {
                AppLogger.i(TAG, "Unmount path: legacy (no snapshot present)")
            }
            return legacyUnmount(context)
        }

        // Context-less fallback (no snapshot available): heuristic cleanup
        AppLogger.w(TAG, "Unmount called without context, using heuristic cleanup")
        return if (isOplusDevice()) {
            clearGenericLuns()
            unmountViaOplusConfigfs(null)
        } else {
            legacyUnmount(null)
        }
    }

    private fun clearGenericLuns() {
        Shell.cmd(
            "for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0; do",
            "  if [ -f \"\$lun/file\" ]; then echo '' > \"\$lun/file\" 2>/dev/null || true; fi",
            "done"
        ).exec()
    }

    /**
     * OPlus fallback unmount without a snapshot: detach UDC, remove the
     * mass_storage.0 symlink from the config, clear the LUN file, re-attach UDC.
     */
    private fun unmountViaOplusConfigfs(context: Context?): Pair<Boolean, String> {
        val gadget = findG1GadgetDir()
            ?: return Pair(false, context?.getString(R.string.err_gadget_not_found_short) ?: "OPlus: usb_gadget/g1 not found on any configfs mount")

        // remove a possible leftover pstick gadget and free the UDC for g1
        teardownPstick(gadget)

        val funcDir = "$gadget/functions/mass_storage.0"
        val savedCtrl = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_USB_CONTROLLER, "") ?: ""

        val script = ArrayList<String>()
        script.addAll(listOf(
            // find the config that currently holds the mass_storage.0 link
            "CONF=''",
            "for c in '$gadget'/configs/*; do if [ -e \"\$c/mass_storage.0\" ]; then CONF=\"\$c\"; break; fi; done",
            "CTRL='$savedCtrl'",
            "[ -z \"\$CTRL\" ] && CTRL=\$(getprop sys.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(getprop vendor.usb.controller)",
            "[ -z \"\$CTRL\" ] && CTRL=\$(ls /sys/class/udc 2>/dev/null | head -n1)",
            // detach, clean
            "echo '' > '$gadget/UDC' 2>/dev/null || true",
            "sleep 1",
            "[ -n \"\$CONF\" ] && rm -f \"\$CONF/mass_storage.0\" 2>/dev/null || true",
            "echo '' > '$funcDir/lun.0/file' 2>/dev/null || true",
            "echo n > '$funcDir/lun.0/ro' 2>/dev/null || true",
            "echo n > '$funcDir/lun.0/cdrom' 2>/dev/null || true"
        ))
        // cycle sys.usb.config so init rebuilds the system composition and
        // rebinds the UDC (guards against the HAL having stripped g1's links)
        script.addAll(buildRecomposeLines(gadget, ""))
        // manual rebind fallback if init did not compose
        script.addAll(listOf(
            "if [ -z \"\$(cat '$gadget/UDC' 2>/dev/null)\" ] && [ -n \"\$CTRL\" ]; then",
            "  echo \"\$CTRL\" > '$gadget/UDC' 2>/dev/null || true",
            "  sleep 1",
            "fi",
            "sleep 1",
            "LUNVAL=\$(cat '$funcDir/lun.0/file' 2>/dev/null)",
            "UDCVAL=\$(cat '$gadget/UDC' 2>/dev/null)",
            "if [ -z \"\$LUNVAL\" ] && [ -n \"\$UDCVAL\" ]; then echo 'UNMOUNT_OK'; else echo \"UNMOUNT_FAIL|\$LUNVAL|\$UDCVAL\"; fi"
        ))

        val res = Shell.cmd(*script.toTypedArray()).exec()
        logShell("OPlus fallback unmount script", res.code, res.out, res.err)
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_DIRECT_MODE, false)?.apply()

        return if (res.out.any { it.startsWith("UNMOUNT_OK") }) {
            AppLogger.i(TAG, "OPlus fallback unmount succeeded")
            Pair(true, context?.getString(R.string.msg_unmounted_oplus) ?: "Unmounted via OPlus configfs")
        } else {
            val failLine = res.out.firstOrNull { it.startsWith("UNMOUNT_FAIL") } ?: ""
            Pair(false, context?.getString(R.string.msg_oplus_unmount_failed, failLine, res.err.joinToString("\n")) ?: "OPlus unmount failed: $failLine ${res.err.joinToString("\n")}")
        }
    }

    /**
     * Legacy unmount used when no snapshot exists (swy gadget, old installs).
     */
    private fun legacyUnmount(context: Context?): Pair<Boolean, String> {
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
        logShell("Legacy unmount script", result.code, result.out, result.err)
        if (context != null) {
            restoreOriginalUsbConfig(context)
        } else {
            val defConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val restoredConfig = defConfig.replace("mass_storage,", "").replace(",mass_storage", "").replace("mass_storage", "none")
            Shell.cmd("setprop sys.usb.config $restoredConfig").exec()
        }

        return if (result.isSuccess) {
            Pair(true, context?.getString(R.string.msg_unmounted) ?: "Unmounted successfully")
        } else {
            val errText = result.err.joinToString("\n").ifEmpty { result.out.joinToString("\n").ifEmpty { "Exit code ${result.code}" } }
            Pair(false, context?.getString(R.string.msg_unmount_failed, result.code, errText) ?: "Unmount failed (exit code ${result.code}): $errText")
        }
    }

    private fun saveAndEnableMassStorageConfig(context: Context) {
        try {
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            if (!curConfig.contains("mass_storage")) {
                prefs.edit().putString(KEY_ORIG_USB_CONFIG, curConfig).apply()
                val targetConfig = if (curConfig.contains("adb")) "mass_storage,adb" else "mass_storage"
                AppLogger.i(TAG, "Toggling sys.usb.config: '$curConfig' -> '$targetConfig' (saved original)")
                Shell.cmd("setprop sys.usb.config $targetConfig").exec()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error toggling sys.usb.config: ${e.message}", e)
        }
    }

    private fun restoreOriginalUsbConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DIRECT_MODE, false)) {
                // Direct configfs mode never altered sys.usb.config; nothing to restore
                return
            }
            val origConfig = prefs.getString(KEY_ORIG_USB_CONFIG, "") ?: ""
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: ""

            val restoreTarget = when {
                origConfig.isNotEmpty() -> origConfig
                curConfig.contains("adb") -> "adb"
                else -> "none"
            }
            AppLogger.i(TAG, "Restoring sys.usb.config to: $restoreTarget")
            Shell.cmd("setprop sys.usb.config $restoreTarget").exec()
            prefs.edit().remove(KEY_ORIG_USB_CONFIG).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error restoring sys.usb.config: ${e.message}", e)
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
            // dedicated pstick gadget: mounted == LUN set AND still holding the UDC
            // (if the HAL somehow reclaimed the UDC the mount is dead and we
            // must NOT report it as mounted)
            "for psg in /config/usb_gadget/pstick \$CONFIGFS/usb_gadget/pstick /sys/kernel/config/usb_gadget/pstick; do",
            "  if [ -d \"\$psg\" ] && [ -n \"\$(cat \"\$psg/UDC\" 2>/dev/null)\" ] && [ -n \"\$(cat \"\$psg/functions/mass_storage.0/lun.0/file\" 2>/dev/null)\" ]; then",
            "    LUN_FILE=\$(cat \"\$psg/functions/mass_storage.0/lun.0/file\" 2>/dev/null)",
            "    LUN_RO=\$(cat \"\$psg/functions/mass_storage.0/lun.0/ro\" 2>/dev/null)",
            "    LUN_CD=\$(cat \"\$psg/functions/mass_storage.0/lun.0/cdrom\" 2>/dev/null)",
            "    LUN_PATH=\"\$psg/functions/mass_storage.0/lun.0\"",
            "    break",
            "  fi",
            "done",
            "if [ -z \"\$LUN_FILE\" ]; then",
            "for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 \$CONFIGFS/usb_gadget/g1/functions/mass_storage.0/lun.0 \$CONFIGFS/usb_gadget/swy/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/class/android_usb/android0/f_mass_storage/lun0; do",
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
            "fi",
            "echo \"STATUS|\$LUN_FILE|\$LUN_RO|\$LUN_CD|\$LUN_PATH\""
        )

        val res = Shell.cmd(*checkScript).exec()
        val line = res.out.firstOrNull { it.startsWith("STATUS|") }
        AppLogger.d(TAG, "Mount status probe: ${line ?: "no STATUS line"}")
        if (res.isSuccess) {
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
