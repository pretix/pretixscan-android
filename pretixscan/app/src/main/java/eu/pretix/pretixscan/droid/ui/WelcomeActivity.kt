package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import com.google.android.material.snackbar.Snackbar
import eu.pretix.libpretixui.android.scanning.defaultToScanner
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val STORE_CONSENT = "consent"
    }

    lateinit var binding: ActivityWelcomeBinding

    public override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_welcome)
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

        val conf = AppConfig(this)
        if (defaultToScanner()) {
            conf.useCamera = false
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            conf.useCamera = false
        }
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager?
            if (cameraManager == null || cameraManager.cameraIdList.size == 0) {
                conf.useCamera = false
            }
        } catch (_: Exception) {
            // ignore
        }

        binding.button.setOnClickListener {
            val needAskingForCameraPermission = conf.useCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED

            if (!needAskingForCameraPermission) {
                continueToSetup()
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                showCameraPermissionSnackbar()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.disclaimer1 = savedInstanceState?.getBoolean(STORE_CONSENT, false) ?: false
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (!granted) {
            showCameraPermissionSnackbar()
        } else {
            continueToSetup()
        }
    }

    fun showCameraPermissionSnackbar() {
        Snackbar
            .make(binding.content, getString(eu.pretix.libpretixui.android.R.string.setup_camera_permission_needed), Snackbar.LENGTH_LONG)
            .setAction(eu.pretix.libpretixui.android.R.string.action_manual) {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.setData("package:$packageName".toUri())
                startActivity(intent)
            }
            .show()
    }

    fun continueToSetup() {
        val intent = Intent(this, SetupActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STORE_CONSENT, binding.disclaimer1)
    }
}
