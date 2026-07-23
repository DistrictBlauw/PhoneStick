package mingww64.phonestick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedImagePath: String = ""
    private var isCurrentlyMounted: Boolean = false

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra("path") ?: ""
            val autoMount = result.data?.getBooleanExtra("auto_mount", false) ?: false

            Log.d("MainActivity", "selectImageLauncher path=$path, autoMount=$autoMount")

            selectedImagePath = path
            saveSelectedImagePath(path)
            refreshStatus()

            if (autoMount && selectedImagePath.isNotEmpty()) {
                mountSelectedImage()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved image path
        selectedImagePath = getSharedPreferences("phonestick", MODE_PRIVATE)
            .getString("last_image_path", "") ?: ""

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupListeners() {
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_licenses -> {
                    startActivity(Intent(this, LicenseActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnSelectImage.setOnClickListener {
            openImageChooser()
        }

        binding.cardSelectedImage.setOnClickListener {
            openImageChooser()
        }

        binding.btnMount.setOnClickListener {
            if (selectedImagePath.isEmpty()) {
                Toast.makeText(this, R.string.file_picker_nofile, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mountSelectedImage()
        }

        binding.btnUnmount.setOnClickListener {
            unmountGadget()
        }
    }

    private fun openImageChooser() {
        val intent = Intent(this, ImageChooserActivity::class.java).apply {
            putExtra("selected_path", selectedImagePath)
        }
        selectImageLauncher.launch(intent)
    }

    private fun refreshStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            
            // Check if stored image file still exists
            if (selectedImagePath.isNotEmpty() && !selectedImagePath.startsWith("content://")) {
                if (!File(selectedImagePath).exists()) {
                    selectedImagePath = ""
                    saveSelectedImagePath("")
                }
            }

            withContext(Dispatchers.Main) {
                isCurrentlyMounted = status.isMounted

                if (status.isMounted) {
                    binding.tvStatusBadge.text = getString(R.string.status_mounted)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_mounted))
                    binding.tvLunInfo.text = getString(R.string.status_lun_path, status.lunPath)
                    if (selectedImagePath.isEmpty()) {
                        selectedImagePath = status.currentFile
                    }
                } else {
                    binding.tvStatusBadge.text = getString(R.string.status_unmounted)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_unmounted))
                    binding.tvLunInfo.text = getString(R.string.status_lun_path, "None")
                }
                updateUi()
            }
        }
    }

    private fun updateUi() {
        val hasSelectedImage = selectedImagePath.isNotEmpty()

        if (hasSelectedImage) {
            if (selectedImagePath.startsWith("content://")) {
                val decoded = Uri.decode(selectedImagePath)
                var rawName = decoded.substringAfterLast('/').substringAfterLast("%3F").substringAfterLast('?')
                if (rawName.contains(':')) {
                    rawName = rawName.substringAfterLast(':')
                }
                binding.tvSelectedFileName.text = if (rawName.isNotBlank()) rawName else "Imported Document"
                binding.tvSelectedPath.text = decoded
            } else {
                val file = File(selectedImagePath)
                if (file.exists()) {
                    binding.tvSelectedFileName.text = file.name
                    val sizeStr = formatFileSize(file.length())
                    binding.tvSelectedPath.text = "$sizeStr • ${file.parent ?: ""}"
                } else {
                    binding.tvSelectedFileName.text = getString(R.string.file_picker_nofile)
                    binding.tvSelectedPath.text = ""
                }
            }
        } else {
            binding.tvSelectedFileName.text = getString(R.string.file_picker_nofile)
            binding.tvSelectedPath.text = ""
        }

        updateFadedState(isMounted = isCurrentlyMounted, hasSelectedImage = hasSelectedImage)
    }

    private fun updateFadedState(isMounted: Boolean, hasSelectedImage: Boolean) {
        val mountEnabled = hasSelectedImage && !isMounted
        val unmountEnabled = isMounted
        val settingsEnabled = hasSelectedImage && !isMounted

        setElementState(binding.btnMount, mountEnabled)
        setElementState(binding.btnUnmount, unmountEnabled)
        setElementState(binding.switchReadOnly, settingsEnabled)
        setElementState(binding.switchCdrom, settingsEnabled)
    }

    private fun setElementState(view: View, isEnabled: Boolean) {
        view.isEnabled = isEnabled
        val targetAlpha = if (isEnabled) 1.0f else 0.4f
        view.animate().alpha(targetAlpha).setDuration(200).start()
    }

    private fun mountSelectedImage() {
        val readOnly = binding.switchReadOnly.isChecked
        val cdrom = binding.switchCdrom.isChecked

        setLoading(true, "Mounting USB Mass Storage...")

        lifecycleScope.launch(Dispatchers.IO) {
            val (_, message) = UsbGadgetController.mountImage(this@MainActivity, selectedImagePath, readOnly, cdrom)
            withContext(Dispatchers.Main) {
                setLoading(false)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun unmountGadget() {
        setLoading(true, "Unmounting USB Mass Storage...")

        lifecycleScope.launch(Dispatchers.IO) {
            val (_, message) = UsbGadgetController.unmountImage(this@MainActivity)
            withContext(Dispatchers.Main) {
                setLoading(false)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    private fun setLoading(isLoading: Boolean, message: String = "") {
        if (isLoading) {
            binding.progressIndicator.visibility = View.VISIBLE
            binding.btnMount.isEnabled = false
            binding.btnUnmount.isEnabled = false
            binding.btnSelectImage.isEnabled = false
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.progressIndicator.visibility = View.GONE
            updateFadedState(isCurrentlyMounted, selectedImagePath.isNotEmpty())
            binding.btnSelectImage.isEnabled = true
        }
    }

    private fun saveSelectedImagePath(path: String) {
        getSharedPreferences("phonestick", MODE_PRIVATE)
            .edit()
            .putString("last_image_path", path)
            .apply()
    }

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
}
