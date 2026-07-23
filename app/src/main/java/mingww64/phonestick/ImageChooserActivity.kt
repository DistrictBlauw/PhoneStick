package mingww64.phonestick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityImageChooserBinding
import mingww64.phonestick.databinding.DialogCreateImageBinding
import java.io.File

class ImageChooserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageChooserBinding
    private lateinit var adapter: ImageFilesAdapter
    private val imageFiles = mutableListOf<File>()
    private var isSpeedDialOpen = false
    private var currentlySelectedPath: String = ""

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageChooserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentlySelectedPath = intent.getStringExtra("selected_path") ?: ""

        binding.toolbar.setNavigationOnClickListener { finishWithResult() }

        setupSpeedDial()
        setupRecyclerView()
        loadImages()
    }

    private fun setupSpeedDial() {
        binding.fabMain.setOnClickListener {
            toggleSpeedDial()
        }

        binding.fabOverlay.setOnClickListener {
            if (isSpeedDialOpen) toggleSpeedDial()
        }

        binding.fabImportImage.setOnClickListener {
            toggleSpeedDial()
            importFileLauncher.launch("*/*")
        }

        binding.fabCreateImage.setOnClickListener {
            toggleSpeedDial()
            showCreateImageDialog()
        }
    }

    private fun toggleSpeedDial() {
        isSpeedDialOpen = !isSpeedDialOpen
        if (isSpeedDialOpen) {
            binding.fabMain.setImageResource(R.drawable.ic_close_vector)

            binding.fabOverlay.visibility = View.VISIBLE
            binding.fabOverlay.alpha = 0f
            binding.fabOverlay.animate().alpha(1f).setDuration(200).start()

            binding.layoutImportImage.visibility = View.VISIBLE
            binding.layoutImportImage.alpha = 0f
            binding.layoutImportImage.translationY = 40f
            binding.layoutImportImage.scaleX = 0.8f
            binding.layoutImportImage.scaleY = 0.8f
            binding.layoutImportImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()

            binding.layoutCreateImage.visibility = View.VISIBLE
            binding.layoutCreateImage.alpha = 0f
            binding.layoutCreateImage.translationY = 40f
            binding.layoutCreateImage.scaleX = 0.8f
            binding.layoutCreateImage.scaleY = 0.8f
            binding.layoutCreateImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .start()

        } else {
            binding.fabMain.setImageResource(R.drawable.ic_add_vector)

            binding.fabOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                binding.fabOverlay.visibility = View.GONE
            }.start()

            binding.layoutImportImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutImportImage.visibility = View.GONE
                }
                .start()

            binding.layoutCreateImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutCreateImage.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupRecyclerView() {
        adapter = ImageFilesAdapter(
            files = imageFiles,
            selectedPath = currentlySelectedPath,
            onFileSelected = { file ->
                selectAndReturnFile(file.absolutePath)
            },
            onFileMount = { file ->
                selectAndReturnFile(file.absolutePath, autoMount = true)
            },
            onFileDeleted = { file ->
                handleDeleteFile(file)
            },
            onFileRenamed = { oldFile, newFile ->
                if (currentlySelectedPath == oldFile.absolutePath) {
                    currentlySelectedPath = newFile.absolutePath
                }
                loadImages()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun handleDeleteFile(file: File) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            val isMountedImage = status.isMounted && (status.currentFile == file.absolutePath || currentlySelectedPath == file.absolutePath)
            
            if (isMountedImage) {
                UsbGadgetController.unmountImage(this@ImageChooserActivity)
            }
            
            removeExternalImagePath(file.absolutePath)
            if (file.exists()) {
                file.delete()
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (currentlySelectedPath == file.absolutePath) {
                    currentlySelectedPath = ""
                }
                loadImages()
                val msg = if (isMountedImage) "Unmounted and removed ${file.name}" else "Removed ${file.name}"
                Toast.makeText(this@ImageChooserActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadImages() {
        val internalFiles = filesDir.listFiles { file ->
            file.isFile && isValidImageExtension(file.extension)
        }?.toList() ?: emptyList()

        val externalPaths = getExternalImagePaths()
        val externalFiles = externalPaths.map { File(it) }.filter { it.exists() && it.isFile && it.length() > 0 }

        val combined = (internalFiles + externalFiles).distinctBy { it.absolutePath }

        imageFiles.clear()
        imageFiles.addAll(combined)
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun handleImportedUri(uri: Uri) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedPath = UriPathResolver.getRealPathFromUri(this@ImageChooserActivity, uri)
            val file = if (resolvedPath.isNotEmpty()) File(resolvedPath) else null

            val isValid = file != null && file.exists() && file.isFile && file.length() > 0 && file.canRead()

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (isValid && file != null) {
                    addExternalImagePath(file.absolutePath)
                    loadImages()
                    selectAndReturnFile(file.absolutePath)
                } else {
                    val errorMsg = when {
                        file == null || !file.exists() -> "Import failed: File does not exist or is unreadable"
                        file.length() <= 0 -> "Import failed: Selected file is 0 bytes empty"
                        !file.canRead() -> "Import failed: Permission denied to read file"
                        else -> "Import failed: Invalid disk image file"
                    }
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("Invalid Image File")
                        .setMessage(errorMsg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun isValidImageExtension(ext: String): Boolean {
        return ext.equals("img", ignoreCase = true) ||
                ext.equals("iso", ignoreCase = true) ||
                ext.equals("bin", ignoreCase = true) ||
                ext.equals("raw", ignoreCase = true) ||
                ext.equals("vhd", ignoreCase = true) ||
                ext.equals("qcow2", ignoreCase = true)
    }

    private fun getExternalImagePaths(): Set<String> {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        return prefs.getStringSet("external_images", emptySet()) ?: emptySet()
    }

    private fun addExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private fun removeExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private fun showCreateImageDialog() {
        val dialogBinding = DialogCreateImageBinding.inflate(layoutInflater)

        dialogBinding.chipGroupSize.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val sizeMb = when (checkedIds.first()) {
                    R.id.chip512m -> 512
                    R.id.chip1g -> 1024
                    R.id.chip2g -> 2048
                    R.id.chip4g -> 4096
                    R.id.chip8g -> 8192
                    else -> 1024
                }
                dialogBinding.etImageSizeMb.setText(sizeMb.toString())
            }
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.create_image_confirm) { _, _ ->
                val name = dialogBinding.etImageName.text.toString().trim()
                val sizeStr = dialogBinding.etImageSizeMb.text.toString().trim()
                val sizeMb = sizeStr.toLongOrNull() ?: 1024L

                if (name.isNotEmpty()) {
                    setLoading(true)
                    ImageCreator.createBlankImage(filesDir, name, sizeMb) { success, msg, file ->
                        setLoading(false)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (success && file != null) {
                            loadImages()
                            selectAndReturnFile(file.absolutePath)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun selectAndReturnFile(path: String, autoMount: Boolean = false) {
        val result = Intent().apply {
            putExtra("path", path)
            putExtra("auto_mount", autoMount)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithResult() {
        val result = Intent().apply {
            putExtra("path", currentlySelectedPath)
            putExtra("auto_mount", false)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithResult()
    }
}
