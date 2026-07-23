package mingww64.phonestick

import android.content.Intent
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mingww64.phonestick.databinding.ActivityLicensesBinding
import mingww64.phonestick.databinding.DialogLicenseBinding
import java.io.InputStreamReader

class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val licenseList = mutableListOf<LicenseItem>()
        try {
            val xrp = resources.getXml(R.xml.licenses)
            while (xrp.eventType != XmlResourceParser.END_DOCUMENT) {
                if (xrp.eventType == XmlResourceParser.START_TAG && xrp.name == "license") {
                    licenseList.add(
                        LicenseItem(
                            name = xrp.getAttributeValue(null, "name") ?: "",
                            type = xrp.getAttributeValue(null, "type"),
                            file = xrp.getAttributeValue(null, "file"),
                            url = xrp.getAttributeValue(null, "url")
                        )
                    )
                }
                xrp.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = LicenseAdapter(licenseList) { lic ->
            showLicenseDialog(lic)
        }
    }

    private fun showLicenseDialog(lic: LicenseItem) {
        val dialogBinding = DialogLicenseBinding.inflate(layoutInflater)
        try {
            if (!lic.file.isNullOrEmpty()) {
                val text = InputStreamReader(assets.open("licenses/${lic.file}")).readText()
                dialogBinding.textView.text = text
                dialogBinding.textView.movementMethod = ScrollingMovementMethod()
            }
        } catch (e: Exception) {
            dialogBinding.textView.text = lic.url ?: ""
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(lic.name)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.ok, null)

        if (!lic.url.isNullOrEmpty()) {
            builder.setPositiveButton(R.string.licenses_upstream) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lic.url)))
            }
        }

        builder.show()
    }

    data class LicenseItem(
        val name: String,
        val type: String?,
        val file: String?,
        val url: String?
    )

    private class LicenseAdapter(
        private val items: List<LicenseItem>,
        private val onItemClick: (LicenseItem) -> Unit
    ) : RecyclerView.Adapter<LicenseAdapter.ViewHolder>() {

        class ViewHolder(val view: View, val title: TextView, val summary: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_2, parent, false
            )
            return ViewHolder(
                view,
                view.findViewById(android.R.id.text1),
                view.findViewById(android.R.id.text2)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            holder.summary.text = item.type ?: ""
            holder.view.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
