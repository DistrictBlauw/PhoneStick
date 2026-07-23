package mingww64.phonestick

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object UriPathResolver {
    private const val TAG = "UriPathResolver"

    fun getRealPathFromUri(context: Context, uri: Uri): String {
        Log.d(TAG, "Resolving URI: $uri")

        // 1. Direct file scheme
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrEmpty() && File(path).exists()) {
                return path
            }
        }

        // 2. DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)

            // ExternalStorageProvider
            if ("com.android.externalstorage.documents" == uri.authority) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]

                    if ("primary".equals(type, ignoreCase = true)) {
                        val path = "${Environment.getExternalStorageDirectory()}/$relativePath"
                        if (File(path).exists()) return path
                        val altPath = "/storage/emulated/0/$relativePath"
                        if (File(altPath).exists()) return altPath
                    } else {
                        val path = "/storage/$type/$relativePath"
                        if (File(path).exists()) return path
                        val mediaRwPath = "/mnt/media_rw/$type/$relativePath"
                        if (File(mediaRwPath).exists()) return mediaRwPath
                    }
                }
            }
            // DownloadsProvider
            else if ("com.android.providers.downloads.documents" == uri.authority) {
                if (docId.startsWith("raw:")) {
                    val rawPath = docId.substring(4)
                    if (File(rawPath).exists()) return rawPath
                }
                try {
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"),
                        docId.toLongOrNull() ?: 0L
                    )
                    val path = getDataColumn(context, contentUri, null, null)
                    if (!path.isNullOrEmpty() && File(path).exists()) return path
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve downloads provider URI: ${e.message}")
                }
            }
            // MediaProvider
            else if ("com.android.providers.media.documents" == uri.authority) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]
                    val contentUri = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(id)
                    val path = getDataColumn(context, contentUri, selection, selectionArgs)
                    if (!path.isNullOrEmpty() && File(path).exists()) return path
                }
            }
        }
        // Generic content scheme query
        else if ("content".equals(uri.scheme, ignoreCase = true)) {
            val path = getDataColumn(context, uri, null, null)
            if (!path.isNullOrEmpty() && File(path).exists()) return path
        }

        // 3. Fallback: Copy to app internal storage cache if direct filesystem path not readable by kernel
        return copyUriToAppCache(context, uri)
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(column)
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDataColumn failed: ${e.message}")
        }
        return null
    }

    private fun copyUriToAppCache(context: Context, uri: Uri): String {
        try {
            val decoded = Uri.decode(uri.toString())
            var fileName = decoded.substringAfterLast('/').substringAfterLast("%3F").substringAfterLast('?')
            if (fileName.contains(':')) fileName = fileName.substringAfterLast(':')
            if (!fileName.endsWith(".img", true) && !fileName.endsWith(".iso", true)) {
                fileName = "imported_$fileName.img"
            }

            val targetFile = File(context.filesDir, fileName)
            Log.i(TAG, "Copying SAF URI to internal storage cache: ${targetFile.absolutePath}")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy SAF URI: ${e.message}", e)
        }
        return uri.toString()
    }
}
