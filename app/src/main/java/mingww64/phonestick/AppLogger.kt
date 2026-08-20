package mingww64.phonestick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Application-wide ring-buffer logger with persistence and export support.
 *
 * Every entry goes to logcat as well as an in-memory ring buffer that is
 * mirrored to a plain-text file in app-private storage, so logs survive
 * process restarts and can be exported / shared from LogActivity.
 */
object AppLogger {

    enum class Level(val label: String) { D("D"), I("I"), W("W"), E("E") }

    data class Entry(val time: Long, val level: Level, val tag: String, val message: String)

    private const val MAX_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 512 * 1024
    private val lineFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val entryPattern: Pattern = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([DIWE])/([^:]+): (.*)$"
    )
    private val parseFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var logFile: File? = null
    @Volatile private var initialized = false

    // ------------------------------------------------------------------
    // Init / persistence
    // ------------------------------------------------------------------

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        logFile = File(File(context.filesDir, "logs").apply { mkdirs() }, "phonestick.log")
        val file = logFile
        ioExecutor.execute { loadFromFile(file) }
    }

    private fun loadFromFile(file: File?) {
        if (file == null || !file.exists()) return
        try {
            val loaded = ArrayList<Entry>()
            for (line in file.readLines()) {
                val m = entryPattern.matcher(line)
                if (m.matches()) {
                    val time = try {
                        parseFormat.parse(m.group(1))?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    val level = Level.entries.firstOrNull { it.label == m.group(2) } ?: Level.I
                    loaded.add(Entry(time, level, m.group(3) ?: "?", m.group(4) ?: ""))
                } else if (loaded.isNotEmpty()) {
                    // continuation line of a multi-line entry (stack trace etc.)
                    val last = loaded.removeAt(loaded.size - 1)
                    loaded.add(last.copy(message = last.message + "\n" + line))
                }
            }
            synchronized(lock) {
                val pre = ArrayList(entries)
                entries.clear()
                loaded.forEach { entries.addLast(it) }
                pre.forEach { entries.addLast(it) }
                trimLocked()
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to load persisted log: ${e.message}", e)
        }
    }

    private fun appendToFile(entry: Entry) {
        val file = logFile ?: return
        try {
            file.appendText(formatLine(entry) + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                val lines = file.readLines()
                val keep = lines.drop(lines.size / 2)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(keep.joinToString("\n") + "\n")
                if (!tmp.renameTo(file)) {
                    file.writeText(keep.joinToString("\n") + "\n")
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to persist log entry: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Logging API
    // ------------------------------------------------------------------

    fun d(tag: String, msg: String) = log(Level.D, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg, null)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Level.E, tag, msg, tr)

    private fun log(level: Level, tag: String, msg: String, tr: Throwable?) {
        // mirror to logcat
        when (level) {
            Level.D -> Log.d(tag, msg, tr)
            Level.I -> Log.i(tag, msg, tr)
            Level.W -> Log.w(tag, msg, tr)
            Level.E -> Log.e(tag, msg, tr)
        }
        val text = if (tr != null) {
            val stack = Log.getStackTraceString(tr).trim().lines().take(15).joinToString("\n")
            msg + "\n" + stack
        } else msg

        val entry = Entry(System.currentTimeMillis(), level, tag, text)
        synchronized(lock) {
            entries.addLast(entry)
            trimLocked()
        }
        ioExecutor.execute { appendToFile(entry) }
    }

    /** Log a shell command result including exit code, stdout and stderr. */
    fun shell(tag: String, label: String, code: Int, out: List<String>, err: List<String>) {
        val sb = StringBuilder("$label (exit=$code)")
        if (out.isNotEmpty()) sb.append("\n  stdout: ").append(out.joinToString(" | "))
        if (err.isNotEmpty()) sb.append("\n  stderr: ").append(err.joinToString(" | "))
        log(if (code == 0) Level.D else Level.W, tag, sb.toString(), null)
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    // ------------------------------------------------------------------
    // Access / export
    // ------------------------------------------------------------------

    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear(context: Context) {
        synchronized(lock) { entries.clear() }
        ioExecutor.execute {
            logFile?.delete()
            // also drop previously shared export files
            val sharedDir = File(context.cacheDir, "logs")
            sharedDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun formatLine(e: Entry): String =
        "${lineFormat.format(Date(e.time))} ${e.level.label}/${e.tag}: ${e.message}"

    fun exportText(): String = synchronized(lock) {
        if (entries.isEmpty()) "" else entries.joinToString("\n") { formatLine(it) } + "\n"
    }

    fun suggestedFileName(): String = "phonestick_log_${fileStamp.format(Date())}.txt"

    /** Write the full log to a user-chosen SAF destination. */
    fun writeToUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(exportText().toByteArray(Charsets.UTF_8))
                os.flush()
            } != null
        } catch (ex: Exception) {
            e("AppLogger", "writeToUri failed: ${ex.message}", ex)
            false
        }
    }

    /** Build an ACTION_SEND intent carrying the log as a cached-file attachment. */
    fun createShareIntent(context: Context): Intent? {
        return try {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, suggestedFileName())
            file.writeText(exportText())
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (ex: Exception) {
            e("AppLogger", "createShareIntent failed: ${ex.message}", ex)
            null
        }
    }
}
