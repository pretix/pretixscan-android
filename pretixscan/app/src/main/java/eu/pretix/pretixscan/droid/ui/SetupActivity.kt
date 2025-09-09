package eu.pretix.pretixscan.droid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import eu.pretix.libpretixsync.setup.SetupManager
import eu.pretix.libpretixui.android.setup.SetupCallable
import eu.pretix.libpretixui.android.setup.SetupFragment
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import io.sentry.Sentry
import java.io.IOException
import java.lang.Exception

class SetupActivity : AppCompatActivity(), SetupCallable {
    companion object {
        const val FRAGMENT_TAG = "SetupFragment"
    }

    private val dataWedgeHelper = DataWedgeHelper(this)

    public override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_fragment)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayShowTitleEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.content)
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

        if (savedInstanceState == null) {
            val args = bundleOf(
                SetupFragment.ARG_DEFAULT_HOST to if (BuildConfig.APPLICATION_ID.contains("eu.pretix")) "https://pretix.eu" else ""
            )
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace<SetupFragment>(R.id.content, FRAGMENT_TAG, args)
            }
        }

        if (dataWedgeHelper.isInstalled) {
            try {
                dataWedgeHelper.install()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val frag = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
        if (frag != null && (frag as SetupFragment).dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun config(useCamera: Boolean) {
        val conf = AppConfig(this)
        conf.scanEngine = if (useCamera) "zxing" else "hardware"
    }

    override fun setup(url: String, token: String) {
        val conf = AppConfig(this)

        val setupm = SetupManager(
            Build.BRAND, Build.MODEL,
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.BASE_OS else "").ifEmpty { "Android" },
            Build.VERSION.RELEASE,
            "pretixSCAN Android",
            BuildConfig.VERSION_NAME,
            AndroidHttpClientFactory(application as PretixScan)
        )

        val init = setupm.initialize(url, token)
        conf.setDeviceConfig(
            init.url,
            init.api_token,
            init.organizer,
            init.device_id,
            init.unique_serial,
            BuildConfig.VERSION_CODE
        )
        conf.deviceKnownName = init.device_name
        conf.deviceKnownGateName = init.gate_name ?: ""
        conf.deviceKnownGateID = init.gate_id ?: 0
        conf.proxyMode = token.startsWith("proxy=")
        if (conf.proxyMode) {
            conf.autoSwitchRequested = false
            conf.syncOrders = false
        }
        if (init.security_profile == "pretixscan_online_kiosk") {
            conf.syncOrders = false
            conf.searchDisabled = true
        } else if (init.security_profile == "pretixscan_online_noorders") {
            conf.syncOrders = false
        }
    }

    override fun onSuccessfulSetup() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onGenericSetupException(e: Exception) {
        Sentry.captureException(e)
    }
}
