package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.Result
import eu.pretix.libpretixsync.setup.*
import eu.pretix.libpretixui.android.scanning.HardwareScanner
import eu.pretix.libpretixui.android.scanning.ScanReceiver
import eu.pretix.pretixscan.droid.*
import eu.pretix.pretixscan.droid.databinding.ActivitySetupBinding
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.dm7.barcodescanner.zxing.ZXingScannerView
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.Exception
import javax.net.ssl.SSLException

class SetupActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {
    lateinit var binding: ActivitySetupBinding
    val bgScope = CoroutineScope(Dispatchers.IO)
    var lastScanTime = 0L
    var lastScanValue = ""
    var conf: AppConfig? = null
    private var ongoing_setup = false
    private val dataWedgeHelper = DataWedgeHelper(this)
    private val LOG_TAG = this::class.java.name

    companion object {
        const val PERMISSIONS_REQUEST_CAMERA = 1337
        const val PERMISSIONS_REQUEST_WRITE_STORAGE = 1338
    }

    private val hardwareScanner = HardwareScanner(object : ScanReceiver {
        override fun scanResult(result: String) {
            handleScan(result)
        }
    })

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        conf = AppConfig(this)

        checkPermission(Manifest.permission.CAMERA, PERMISSIONS_REQUEST_CAMERA)
        if (dataWedgeHelper.isInstalled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERMISSIONS_REQUEST_WRITE_STORAGE)
            } else {
                try {
                    dataWedgeHelper.install()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        binding.btSwitchCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                hardwareScanner.stop(this)
                conf!!.useCamera = true
                binding.scannerView.setResultHandler(this)
                binding.scannerView.startCamera()
                binding.llHardwareScan.visibility = if (conf!!.useCamera) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (conf!!.useCamera) {
                binding.scannerView.setResultHandler(this)
                binding.scannerView.startCamera()
            }
        }
        binding.llHardwareScan.visibility = if (conf!!.useCamera) View.GONE else View.VISIBLE
        hardwareScanner.start(this)
    }

    override fun onPause() {
        super.onPause()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (conf!!.useCamera) {
                binding.scannerView.stopCamera()
            }
        }
        hardwareScanner.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    if (conf!!.useCamera) {
                        binding.scannerView.setResultHandler(this)
                        binding.scannerView.startCamera()
                    }
                    checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, PERMISSIONS_REQUEST_WRITE_STORAGE)
                } else {
                    Toast.makeText(this, "Please grant camera permission to use the QR Scanner", Toast.LENGTH_SHORT).show()
                }
                return
            }
            PERMISSIONS_REQUEST_WRITE_STORAGE -> {
                try {
                    if (dataWedgeHelper.isInstalled) {
                        dataWedgeHelper.install()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    override fun handleResult(rawResult: Result) {
        binding.scannerView.resumeCameraPreview(this)
        if (lastScanValue == rawResult.text && lastScanTime > System.currentTimeMillis() - 3000) {
            return
        }
        lastScanValue = rawResult.text
        lastScanTime = System.currentTimeMillis()
        handleScan(rawResult.text)
    }

    fun handleScan(res: String) {
        try {
            val jd = JSONObject(res)
            if (jd.has("version")) {
                MaterialAlertDialogBuilder(this).setMessage(R.string.setup_error_legacy_qr_code).create().show()
                return
            }
            if (!jd.has("handshake_version")) {
                MaterialAlertDialogBuilder(this).setMessage(R.string.setup_error_invalid_qr_code).create().show()
                return
            }
            if (jd.getInt("handshake_version") > 1) {
                MaterialAlertDialogBuilder(this).setMessage(R.string.setup_error_version_too_high).create().show()
                return
            }
            if (!jd.has("url") || !jd.has("token")) {
                MaterialAlertDialogBuilder(this).setMessage(R.string.setup_error_invalid_qr_code).create().show()
                return
            }
            initialize(jd.getString("url"), jd.getString("token"))
        } catch (e: JSONException) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.setup_error_invalid_qr_code).create().show()
            return
        }
    }

    private fun initialize(url: String, token: String) {
        if (ongoing_setup) {
            Log.w(LOG_TAG, "Ongoing setup. Discarding initialize with ${url} / ${token}.")
            return
        }
        ongoing_setup = true

        val pdialog = ProgressDialog(this).apply {
            isIndeterminate = true
            setMessage(getString(R.string.setup_progress))
            setTitle(R.string.setup_progress)
            setCanceledOnTouchOutside(false)
            setCancelable(false)
        }

        fun resume() {
            pdialog.dismiss()
            binding.scannerView.resumeCameraPreview(this)
            ongoing_setup = false
        }

        pdialog.show()

        val activity = this as SetupActivity
        bgScope.launch {
            val setupm = SetupManager(
                    Build.BRAND, Build.MODEL,
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.BASE_OS else "").ifEmpty { "Android" },
                    Build.VERSION.RELEASE,
                    "pretixSCAN Android", BuildConfig.VERSION_NAME,
                    AndroidHttpClientFactory(application as PretixScan)
            )
            try {
                val init = setupm.initialize(url, token)
                conf!!.setDeviceConfig(init.url, init.api_token, init.organizer, init.device_id, init.unique_serial, BuildConfig.VERSION_CODE)
                conf!!.deviceKnownName = init.device_name
                conf!!.deviceKnownGateName = init.gate_name ?: ""
                conf!!.deviceKnownGateID = init.gate_id ?: 0
                conf!!.proxyMode = token.startsWith("proxy=")
                if (conf!!.proxyMode) {
                    conf!!.autoSwitchRequested = false
                    conf!!.syncOrders = false
                }
                if (init.security_profile == "pretixscan_online_kiosk") {
                    conf!!.syncOrders = false
                    conf!!.searchDisabled = true
                } else if (init.security_profile == "pretixscan_online_noorders") {
                    conf!!.syncOrders = false
                }
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    pdialog.dismiss()

                    val intent = Intent(this@SetupActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK.or(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
                    startActivity(intent)
                    finish()
                }
            } catch (e: SetupBadRequestException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.setup_error_request).create().show()
                }
                return@launch
            } catch (e: SSLException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.setup_error_ssl).create().show()
                }
                return@launch
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.setup_error_io).create().show()
                }
                return@launch
            } catch (e: SetupServerErrorException) {
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.setup_error_server).create().show()
                }
            } catch (e: SetupBadResponseException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.setup_error_response).create().show()
                }
            } catch (e: SetupException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(e.message ?: "Unknown error").create().show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Sentry.captureException(e)
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    MaterialAlertDialogBuilder(activity).setMessage(e.message ?: "Unknown error").create().show()
                }
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_setup, menu)
        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_manual -> {
                MaterialAlertDialogBuilder(this).apply {
                    val view = layoutInflater.inflate(R.layout.dialog_setup_manual, null)
                    val inputUri = view.findViewById<EditText>(R.id.input_uri)
                    if (BuildConfig.APPLICATION_ID.contains("eu.pretix")) {
                        inputUri.setText("https://pretix.eu")
                    }
                    val inputToken = view.findViewById<EditText>(R.id.input_token)
                    setView(view)
                    setPositiveButton(R.string.ok) { dialog, _ ->
                        dialog.dismiss()
                        initialize(inputUri.text.toString(), inputToken.text.toString())
                    }
                    setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.cancel()
                    }
                }.create().show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
