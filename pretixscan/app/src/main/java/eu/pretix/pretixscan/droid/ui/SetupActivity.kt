package eu.pretix.pretixscan.droid.ui

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import eu.pretix.libpretixsync.setup.SetupBadRequestException
import eu.pretix.libpretixsync.setup.SetupBadResponseException
import eu.pretix.libpretixsync.setup.SetupException
import eu.pretix.libpretixsync.setup.SetupManager
import eu.pretix.libpretixsync.setup.SetupServerErrorException
import eu.pretix.libpretixui.android.scanning.defaultToScanner
import eu.pretix.libpretixui.android.setup.SetupCallable
import eu.pretix.libpretixui.android.setup.SetupFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import io.sentry.Sentry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.Exception
import javax.net.ssl.SSLException

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
            ensureSetupFragment()
            handleSetupLink(intent.data)
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
        conf.useCamera = useCamera
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

    private fun ensureSetupFragment() {
        if (supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) != null) {
            return
        }
        val args = bundleOf(
            SetupFragment.ARG_DEFAULT_HOST to if (BuildConfig.APPLICATION_ID.contains("eu.pretix")) "https://pretix.eu" else ""
        )
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace<SetupFragment>(R.id.content, FRAGMENT_TAG, args)
        }
    }

    private fun handleSetupLink(uri: Uri?) {
        if (uri?.scheme?.equals("pretixscan", ignoreCase = true) != true) {
            return
        }
        if (uri.host?.equals("setup", ignoreCase = true) != true) {
            return
        }

        val url = uri.getQueryParameter("url")?.trim()
        val token = uri.getQueryParameter("token")?.trim()

        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            showSetupAlert(R.string.setup_error_invalid_link)
            return
        }
        if (AppConfig(this).deviceRegistered) {
            resumeScanningWithAlreadyConfiguredWarning()
            return
        }
        importSetupLink(url, token)
    }

    private fun importSetupLink(url: String, token: String) {
        val pdialog = ProgressDialog(this).apply {
            isIndeterminate = true
            setMessage(getString(R.string.progress_registering))
            setTitle(R.string.progress_registering)
            setCanceledOnTouchOutside(false)
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    setup(url, token)
                }
                config(!defaultToScanner())
                onSuccessfulSetup()
            } catch (e: Exception) {
                handleSetupException(e)
            } finally {
                if (pdialog.isShowing) {
                    runCatching {
                        pdialog.dismiss()
                    }
                }
            }
        }
    }

    private fun handleSetupException(e: Exception) {
        when (e) {
            is SetupBadRequestException -> showSetupAlert(e.message ?: getString(R.string.setup_error_invalid_link))
            is SSLException -> showSetupAlert(R.string.setup_error_ssl)
            is IOException -> showSetupAlert(R.string.setup_error_io)
            is SetupServerErrorException -> showSetupAlert(R.string.setup_error_server)
            is SetupBadResponseException -> showSetupAlert(R.string.setup_error_response)
            is SetupException -> showSetupAlert(e.message ?: getString(R.string.error_unknown_exception))
            else -> {
                onGenericSetupException(e)
                showSetupAlert(e.message ?: getString(R.string.error_unknown_exception))
            }
        }
    }

    private fun showSetupAlert(messageRes: Int) {
        showSetupAlert(getString(messageRes))
    }

    private fun showSetupAlert(message: CharSequence) {
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun resumeScanningWithAlreadyConfiguredWarning() {
        Toast.makeText(applicationContext, R.string.setup_error_already_configured, Toast.LENGTH_LONG).show()
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainIntent)
        finish()
    }
}
