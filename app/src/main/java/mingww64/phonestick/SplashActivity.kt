package mingww64.phonestick

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRetryRoot.setOnClickListener {
            checkRootAccess()
        }

        binding.btnExitApp.setOnClickListener {
            finish()
        }

        checkRootAccess()
    }

    private fun checkRootAccess() {
        binding.layoutCheckingRoot.visibility = View.VISIBLE
        binding.cardRootGuard.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val isRootGranted = UsbGadgetController.isRootAvailable()

            withContext(Dispatchers.Main) {
                if (isRootGranted) {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.layoutCheckingRoot.visibility = View.GONE
                    binding.cardRootGuard.visibility = View.VISIBLE
                }
            }
        }
    }
}
