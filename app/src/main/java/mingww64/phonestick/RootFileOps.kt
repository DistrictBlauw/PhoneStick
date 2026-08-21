package mingww64.phonestick

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * File operations with automatic root fallback.
 *
 * Imported images may live on root-only paths (e.g. /data/media/0 or
 * /mnt/pass_through/0) that UriPathResolver deliberately rewrites to so the
 * kernel USB gadget can read them. The app process cannot stat those paths,
 * so plain File.exists()/File.length() return false/0 for perfectly valid
 * images. Every helper here first tries the plain File API and falls back to
 * a root shell when that fails.
 */
object RootFileOps {
    private const val TAG = "RootFileOps"

    private fun escape(path: String): String = path.replace("'", "'\\''")

    fun exists(path: String): Boolean {
        if (path.isEmpty()) return false
        if (File(path).exists()) return true
        return try {
            val res = Shell.cmd("if [ -e '${escape(path)}' ]; then echo EXISTS; fi").exec()
            res.out.contains("EXISTS").also {
                AppLogger.d(TAG, "exists(root) '$path' = $it")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "exists(root) failed for '$path': ${e.message}")
            false
        }
    }

    fun length(path: String): Long {
        if (path.isEmpty()) return 0L
        val local = File(path).length()
        if (local > 0) return local
        return try {
            val res = Shell.cmd("stat -c %s '${escape(path)}' 2>/dev/null || echo 0").exec()
            val size = res.out.lastOrNull()?.trim()?.toLongOrNull() ?: 0L
            AppLogger.d(TAG, "length(root) '$path' = $size")
            size
        } catch (e: Exception) {
            AppLogger.e(TAG, "length(root) failed for '$path': ${e.message}")
            0L
        }
    }

    /**
     * Batch-fetch file sizes in a single root shell call.
     * Returns a map of absolute path -> size in bytes.
     */
    fun statSizes(paths: List<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        val script = StringBuilder()
        for (p in paths) {
            script.append("stat -c '%s|%n' '").append(escape(p)).append("' 2>/dev/null; ")
        }
        return try {
            val res = Shell.cmd(script.toString()).exec()
            for (line in res.out) {
                val sep = line.indexOf('|')
                if (sep > 0) {
                    val size = line.substring(0, sep).trim().toLongOrNull() ?: continue
                    result[line.substring(sep + 1)] = size
                }
            }
            AppLogger.d(TAG, "statSizes: fetched ${result.size}/${paths.size} sizes")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "statSizes failed: ${e.message}")
            result
        }
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        if (f.exists()) return f.delete()
        return try {
            Shell.cmd("rm -f '${escape(path)}'").exec().isSuccess.also {
                AppLogger.d(TAG, "delete(root) '$path' = $it")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "delete(root) failed for '$path': ${e.message}")
            false
        }
    }

    fun rename(oldPath: String, newPath: String): Boolean {
        val old = File(oldPath)
        if (old.exists()) return old.renameTo(File(newPath))
        return try {
            Shell.cmd("mv '${escape(oldPath)}' '${escape(newPath)}'").exec().isSuccess.also {
                AppLogger.d(TAG, "rename(root) '$oldPath' -> '$newPath' = $it")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "rename(root) failed for '$oldPath': ${e.message}")
            false
        }
    }
}
