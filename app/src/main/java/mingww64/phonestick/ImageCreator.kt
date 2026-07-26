package mingww64.phonestick

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.concurrent.thread

object ImageCreator {
    private const val TAG = "ImageCreator"

    fun createBlankImage(
        targetDirectory: File,
        fileName: String,
        sizeInMB: Long,
        filesystemFormat: String = "FAT32",
        useFastAllocation: Boolean = true,
        onProgressStatus: (status: String, percent: Int, writtenMb: Long, totalMb: Long) -> Unit = { _, _, _, _ -> },
        onComplete: (Boolean, String, File?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
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

        thread {
            val totalBytes = sizeInMB * 1024 * 1024L
            val escapedPath = outputFile.absolutePath.replace("'", "'\\''")
            var creationSuccess = false

            // Try Fast Allocation (fallocate) if requested
            if (useFastAllocation) {
                mainHandler.post {
                    onProgressStatus("Allocating file with fallocate...", 10, 0, sizeInMB)
                }

                val fallocateCmd = "export PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin; fallocate -l ${sizeInMB}M '$escapedPath'"
                val fallocateRes = Shell.cmd(fallocateCmd).exec()

                if (fallocateRes.isSuccess && outputFile.exists() && outputFile.length() == totalBytes) {
                    Log.d(TAG, "fallocate succeeded for $escapedPath")
                    creationSuccess = true
                    mainHandler.post {
                        onProgressStatus("Fast allocation complete", 90, sizeInMB, sizeInMB)
                    }
                } else {
                    Log.w(TAG, "fallocate failed or unsupported: ${fallocateRes.err.joinToString("\n")}. Falling back to dd.")
                    if (outputFile.exists()) outputFile.delete()
                }
            }

            // Fall back to dd if fallocate was not requested or failed
            if (!creationSuccess) {
                var isFinished = false
                val progressThread = thread {
                    while (!isFinished) {
                        if (outputFile.exists()) {
                            val currentBytes = outputFile.length()
                            val writtenMb = currentBytes / (1024 * 1024)
                            val percent = if (totalBytes > 0) {
                                ((currentBytes.toDouble() / totalBytes) * 90).toInt().coerceIn(0, 90)
                            } else 0
                            mainHandler.post {
                                onProgressStatus("Creating image (dd)... $writtenMb MB / $sizeInMB MB ($percent%)", percent, writtenMb, sizeInMB)
                            }
                        }
                        try {
                            Thread.sleep(150)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }

                val ddCmd = "export PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin; dd if=/dev/zero of='$escapedPath' bs=1M count=$sizeInMB"
                val ddRes = Shell.cmd(ddCmd).exec()
                isFinished = true
                progressThread.interrupt()

                if (ddRes.isSuccess && outputFile.exists() && outputFile.length() > 0) {
                    creationSuccess = true
                } else {
                    Log.e(TAG, "dd creation failed: ${ddRes.err.joinToString("\n")}")
                    if (outputFile.exists()) outputFile.delete()
                    mainHandler.post {
                        onComplete(false, "Failed to create image file: ${ddRes.err.joinToString("\n")}", null)
                    }
                    return@thread
                }
            }

            // Format filesystem with mkfs if selected
            var formatMsg = ""
            val fmt = filesystemFormat.uppercase()
            if (creationSuccess && fmt != "NONE" && fmt != "RAW BLANK (NO FS)") {
                mainHandler.post {
                    onProgressStatus("Formatting filesystem ($fmt)...", 95, sizeInMB, sizeInMB)
                }

                val candidates = when (fmt) {
                    "EXFAT" -> listOf(
                        "mkfs.exfat '$escapedPath'",
                        "toybox mkfs.exfat '$escapedPath'",
                        "busybox mkfs.exfat '$escapedPath'"
                    )
                    "FAT32", "FAT" -> listOf(
                        "newfs_msdos -F 32 -c 1 '$escapedPath'",
                        "LOOP=\$(losetup -f --show '$escapedPath') && newfs_msdos -F 32 -c 1 \$LOOP && losetup -d \$LOOP",
                        "mkfs.vfat -F 32 -I '$escapedPath'",
                        "mkfs.fat -F 32 -I '$escapedPath'",
                        "mkfs.vfat '$escapedPath'",
                        "toybox mkfs.vfat '$escapedPath'",
                        "busybox mkfs.vfat '$escapedPath'"
                    )
                    "EXT4" -> listOf(
                        "mkfs.ext4 -F '$escapedPath'",
                        "mke2fs -t ext4 -F '$escapedPath'",
                        "make_ext4fs '$escapedPath'",
                        "toybox mkfs.ext4 '$escapedPath'",
                        "busybox mkfs.ext4 '$escapedPath'"
                    )
                    else -> emptyList()
                }

                var formatSuccess = false
                var lastError = ""

                for (cmdCandidate in candidates) {
                    val fullCmd = "export PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin; $cmdCandidate"
                    val res = Shell.cmd(fullCmd).exec()
                    if (res.isSuccess) {
                        formatSuccess = true
                        formatMsg = " Formatted as $fmt."
                        Log.d(TAG, "Successfully formatted using: $cmdCandidate")
                        break
                    } else {
                        lastError = res.err.joinToString("\n").ifEmpty { "code ${res.code}" }
                        Log.w(TAG, "Format attempt ($cmdCandidate) failed: $lastError")
                    }
                }

                if (!formatSuccess && candidates.isNotEmpty()) {
                    Log.e(TAG, "All format candidates failed for $fmt. Last error: $lastError")
                    formatMsg = " (Warning: mkfs $fmt failed: $lastError)."
                }
            }

            mainHandler.post {
                onProgressStatus("Complete!", 100, sizeInMB, sizeInMB)
                Log.d(TAG, "Successfully created image at ${outputFile.absolutePath}")
                onComplete(true, "Successfully created image (${outputFile.name}).$formatMsg", outputFile)
            }
        }
    }

    fun formatExistingImage(
        file: File,
        filesystemFormat: String = "FAT32",
        onProgressStatus: (status: String) -> Unit = {},
        onComplete: (Boolean, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread {
            val escapedPath = file.absolutePath.replace("'", "'\\''")
            val fmt = filesystemFormat.uppercase()
            mainHandler.post {
                onProgressStatus("Formatting ${file.name} as $fmt...")
            }

            val candidates = when (fmt) {
                "EXFAT" -> listOf(
                    "mkfs.exfat '$escapedPath'",
                    "toybox mkfs.exfat '$escapedPath'",
                    "busybox mkfs.exfat '$escapedPath'"
                )
                "FAT32", "FAT" -> listOf(
                    "newfs_msdos -F 32 -c 1 '$escapedPath'",
                    "LOOP=\$(losetup -f --show '$escapedPath') && newfs_msdos -F 32 -c 1 \$LOOP && losetup -d \$LOOP",
                    "mkfs.vfat -F 32 -I '$escapedPath'",
                    "mkfs.fat -F 32 -I '$escapedPath'",
                    "mkfs.vfat '$escapedPath'",
                    "toybox mkfs.vfat '$escapedPath'",
                    "busybox mkfs.vfat '$escapedPath'"
                )
                "EXT4" -> listOf(
                    "mkfs.ext4 -F '$escapedPath'",
                    "mke2fs -t ext4 -F '$escapedPath'",
                    "make_ext4fs '$escapedPath'",
                    "toybox mkfs.ext4 '$escapedPath'",
                    "busybox mkfs.ext4 '$escapedPath'"
                )
                else -> emptyList()
            }

            var formatSuccess = false
            var lastError = ""

            for (cmdCandidate in candidates) {
                val fullCmd = "export PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin; $cmdCandidate"
                val res = Shell.cmd(fullCmd).exec()
                if (res.isSuccess) {
                    formatSuccess = true
                    Log.d(TAG, "Successfully formatted ${file.name} using: $cmdCandidate")
                    break
                } else {
                    lastError = res.err.joinToString("\n").ifEmpty { "Exit code ${res.code}" }
                    Log.w(TAG, "Format attempt ($cmdCandidate) failed: $lastError")
                }
            }

            mainHandler.post {
                if (formatSuccess) {
                    onComplete(true, "Successfully formatted ${file.name} as $fmt")
                } else {
                    onComplete(false, "Failed to format ${file.name} as $fmt: $lastError")
                }
            }
        }
    }
}
