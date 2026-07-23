package mingww64.phonestick

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mingww64.phonestick.databinding.ImageChooserRowBinding
import java.io.File

class ImageFilesAdapter(
    private val files: MutableList<File>,
    private val selectedPath: String,
    private val onFileSelected: (File) -> Unit,
    private val onFileMount: (File) -> Unit,
    private val onFileDeleted: (File) -> Unit,
    private val onFileRenamed: (File, File) -> Unit
) : RecyclerView.Adapter<ImageFilesAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ImageChooserRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ImageChooserRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val context = holder.itemView.context

        holder.binding.filename.text = file.name
        holder.binding.fileSize.text = formatFileSize(file.length())

        if (file.extension.equals("iso", ignoreCase = true)) {
            holder.binding.ivFileIcon.setImageResource(R.drawable.ic_disc_vector)
        } else {
            holder.binding.ivFileIcon.setImageResource(R.drawable.ic_sd_storage_vector)
        }

        val isSelected = file.absolutePath == selectedPath

        // Set active box outline stroke and active badge
        if (isSelected) {
            holder.binding.cardItem.strokeWidth = dpToPx(context, 2)
            holder.binding.tvSelectedBadge.visibility = View.VISIBLE
        } else {
            holder.binding.cardItem.strokeWidth = 0
            holder.binding.tvSelectedBadge.visibility = View.GONE
        }

        // Click on item selects the file
        holder.itemView.setOnClickListener {
            onFileSelected(file)
        }

        // Item overflow menu
        holder.binding.btnOverflow.setOnClickListener { view ->
            showPopupMenu(context, view, file)
        }
    }

    private fun showPopupMenu(context: Context, anchorView: View, file: File) {
        val popup = PopupMenu(context, anchorView)
        popup.menu.add("Mount Now")
        popup.menu.add("Rename")
        popup.menu.add("Delete")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Mount Now" -> {
                    onFileMount(file)
                    true
                }
                "Rename" -> {
                    showRenameDialog(context, file)
                    true
                }
                "Delete" -> {
                    showDeleteDialog(context, file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(context: Context, file: File) {
        val input = EditText(context)
        input.setText(file.name)
        input.setSelection(file.name.lastIndexOf('.').let { if (it > 0) it else file.name.length })

        MaterialAlertDialogBuilder(context)
            .setTitle("Rename Image")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val newFile = File(file.parentFile, newName)
                    if (file.renameTo(newFile)) {
                        onFileRenamed(file, newFile)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(context: Context, file: File) {
        val isSelected = file.absolutePath == selectedPath
        val msg = if (isSelected) {
            "\"${file.name}\" is currently active. Deleting it will automatically unmount the USB drive and delete the file. Continue?"
        } else {
            "Are you sure you want to delete \"${file.name}\"?"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Delete Image")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                onFileDeleted(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun getItemCount(): Int = files.size

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$sizeBytes B"
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}