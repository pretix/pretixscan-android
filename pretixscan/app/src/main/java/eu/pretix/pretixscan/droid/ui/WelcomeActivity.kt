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
        if (isSetupDeepLink() && AppConfig(this).deviceRegistered) {
            resumeScanningWithAlreadyConfiguredWarning(this)
            return
        }

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
                startSetupActivity()
            } else {
                checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)
            }
        }

        if (defaultToScanner()) {
            val conf = AppConfig(this)
            conf.useCamera = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    startSetupActivity()
                } else {
                    Toast.makeText(this, R.string.setup_camera_permission_needed, Toast.LENGTH_SHORT).show()
                }
                return
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    private fun startSetupActivity() {
        val setupIntent = Intent(this, SetupActivity::class.java).apply {
            data = intent?.data
        }
        startActivity(setupIntent)
        finish()
    }

    private fun isSetupDeepLink(): Boolean {
        val uri = intent?.data ?: return false
        return uri.scheme.equals("pretixscan", ignoreCase = true) &&
                uri.host.equals("setup", ignoreCase = true)
    }
}
