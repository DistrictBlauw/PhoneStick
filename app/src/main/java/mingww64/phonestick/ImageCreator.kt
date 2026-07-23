package mingww64.phonestick

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

object ImageCreator {
    private const val TAG = "ImageCreator"

    fun createBlankImage(
        targetDirectory: File,
        fileName: String,
        sizeInMB: Long,
        onProgress: (String) -> Unit = {},
        onComplete: (Boolean, String, File?) -> Unit
    ) {
        val cleanName = if (fileName.endsWith(".img", ignoreCase = true) || fileName.endsWith(".iso", ignoreCase = true)) {
            fileName
        } else {
            "$fileName.img"
        }

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val outputFile = File(targetDirectory, cleanName)
        if (outputFile.exists()) {
            onComplete(false, "File already exists: ${outputFile.name}", null)
            return
        }

        onProgress("Creating blank image ${outputFile.name} (${sizeInMB} MB)...")

        val escapedPath = outputFile.absolutePath.replace("'", "'\\''")
        val command = "dd if=/dev/zero of='$escapedPath' bs=1M count=$sizeInMB"

        val result = Shell.cmd(command).exec()

        if (result.isSuccess && outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "Successfully created blank image at ${outputFile.absolutePath}")
            onComplete(true, "Successfully created image (${outputFile.name})", outputFile)
        } else {
            Log.e(TAG, "Failed to create image: ${result.err.joinToString("\n")}")
            if (outputFile.exists()) outputFile.delete()
            onComplete(false, "Failed to create image: ${result.err.joinToString("\n")}", null)
        }
    }
}
