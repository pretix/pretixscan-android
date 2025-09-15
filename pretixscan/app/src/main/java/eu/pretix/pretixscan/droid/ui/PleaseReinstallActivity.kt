package eu.pretix.pretixscan.droid.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import eu.pretix.pretixscan.droid.databinding.ActivityPleaseReinstallBinding
import androidx.core.net.toUri


class PleaseReinstallActivity: AppCompatActivity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPleaseReinstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenAppSettings.setOnClickListener {
            openAppSettings()
        }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.setData("package:$packageName".toUri())
        startActivity(intent)
    }
}