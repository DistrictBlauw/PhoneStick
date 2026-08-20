package mingww64.phonestick

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import mingww64.phonestick.databinding.ActivityLogBinding

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: LogAdapter

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = AppLogger.writeToUri(this, uri)
            if (ok) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.log_export_success, uri.lastPathSegment ?: uri.toString()),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                Snackbar.make(binding.root, R.string.log_export_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_log_share -> {
                    val intent = AppLogger.createShareIntent(this)
                    if (intent != null) {
                        startActivity(Intent.createChooser(intent, getString(R.string.log_share)))
                    } else {
                        Snackbar.make(binding.root, R.string.log_export_failed, Snackbar.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.menu_log_export -> {
                    saveLauncher.launch(AppLogger.suggestedFileName())
                    true
                }
                R.id.menu_log_clear -> {
                    confirmClear()
                    true
                }
                else -> false
            }
        }

        adapter = LogAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = AppLogger.snapshot().asReversed() // newest first
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                AppLogger.clear(this)
                refresh()
                Snackbar.make(binding.root, R.string.log_cleared, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        private var items: List<AppLogger.Entry> = emptyList()

        fun submit(newItems: List<AppLogger.Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.logLine)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.log_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val context = holder.text.context
            holder.text.text = AppLogger.formatLine(entry)
            val color = when (entry.level) {
                AppLogger.Level.D -> ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
                AppLogger.Level.I -> ContextCompat.getColor(context, R.color.md_theme_onSurface)
                AppLogger.Level.W -> 0xFFB26500.toInt() // amber
                AppLogger.Level.E -> 0xFFC62828.toInt() // red
            }
            holder.text.setTextColor(color)
        }

        override fun getItemCount(): Int = items.size
    }
}
