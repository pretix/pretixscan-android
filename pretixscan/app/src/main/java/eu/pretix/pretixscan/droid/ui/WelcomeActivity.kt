package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import eu.pretix.libpretixui.android.scanning.defaultToScanner
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val PERMISSIONS_REQUEST_CAMERA = 1337
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityWelcomeBinding>(this, R.layout.activity_welcome)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.content
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = insets.left,
                right = insets.right,
                top = 0, // handled by AppBar
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        binding.button?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(this, SetupActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)
            }
        }

        if (defaultToScanner()) {
            val conf = AppConfig(this)
            conf.scanEngine = "hardware"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    val intent = Intent(this, SetupActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Please grant camera permission to use the QR Scanner", Toast.LENGTH_SHORT).show();
                }
                return
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }
}
