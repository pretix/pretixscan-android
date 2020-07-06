package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.Result
import eu.pretix.libpretixsync.setup.SetupBadRequestException
import eu.pretix.libpretixsync.setup.SetupBadResponseException
import eu.pretix.libpretixsync.setup.SetupManager
import eu.pretix.libpretixsync.setup.SetupServerErrorException
import eu.pretix.pretixscan.HardwareScanner
import eu.pretix.pretixscan.ScanReceiver
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.R
import io.sentry.Sentry
import kotlinx.android.synthetic.main.activity_setup.*
import me.dm7.barcodescanner.zxing.ZXingScannerView
import org.jetbrains.anko.alert
import org.jetbrains.anko.appcompat.v7.Appcompat
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.indeterminateProgressDialog
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.Exception
import javax.net.ssl.SSLException

class SetupActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {
    var lastScanTime = 0L
    var lastScanValue = ""
    var conf: AppConfig? = null
    private val dataWedgeHelper = DataWedgeHelper(this)

    private val hardwareScanner = HardwareScanner(object : ScanReceiver {
        override fun scanResult(result: String) {
            handleScan(result)
        }
    })

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        conf = AppConfig(this)

        checkPermission(Manifest.permission.CAMERA)
        if (dataWedgeHelper.isInstalled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        1338);
            } else {
                try {
                    dataWedgeHelper.install()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (conf!!.useCamera) {
                scanner_view.setResultHandler(this)
                scanner_view.startCamera()
            }
        }
        tvHardwareScan.visibility = if (conf!!.useCamera) View.GONE else View.VISIBLE
        hardwareScanner.start(this)
    }

    override fun onPause() {
        super.onPause()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (conf!!.useCamera) {
                scanner_view.stopCamera()
            }
        }
        hardwareScanner.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            1337 -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    if (conf!!.useCamera) {
                        scanner_view.setResultHandler(this)
                        scanner_view.startCamera()
                    }
                    checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, 1338)
                } else {
                    Toast.makeText(this, "Please grant camera permission to use the QR Scanner", Toast.LENGTH_SHORT).show();
                }
                return
            }
            1338 -> {
                try {
                    if (dataWedgeHelper.isInstalled) {
                        dataWedgeHelper.install()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            else -> {
            }
        }
    }

    override fun handleResult(rawResult: Result) {
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
                alert(Appcompat, R.string.setup_error_legacy_qr_code).show()
                scanner_view.resumeCameraPreview(this)
                return
            }
            if (!jd.has("handshake_version")) {
                alert(Appcompat, R.string.setup_error_invalid_qr_code).show()
                scanner_view.resumeCameraPreview(this)
                return
            }
            if (jd.getInt("handshake_version") > 1) {
                alert(Appcompat, R.string.setup_error_version_too_high).show()
                scanner_view.resumeCameraPreview(this)
                return
            }
            if (!jd.has("url") || !jd.has("token")) {
                alert(Appcompat, R.string.setup_error_invalid_qr_code).show()
                scanner_view.resumeCameraPreview(this)
                return
            }
            initialize(jd.getString("url"), jd.getString("token"))
        } catch (e: JSONException) {
            alert(Appcompat, R.string.setup_error_invalid_qr_code).show()
            scanner_view.resumeCameraPreview(this)
            return
        }
    }

    private fun initialize(url: String, token: String) {
        val pdialog = indeterminateProgressDialog(R.string.setup_progress)

        fun resume() {
            pdialog.dismiss()
            scanner_view.resumeCameraPreview(this)
        }

        pdialog.setCanceledOnTouchOutside(false)
        pdialog.setCancelable(false)
        doAsync {
            val setupm = SetupManager(
                    Build.BRAND, Build.MODEL, "pretixSCAN", BuildConfig.VERSION_NAME,
                    AndroidHttpClientFactory()
            )
            try {
                val init = setupm.initialize(url, token)
                conf!!.setDeviceConfig(init.url, init.api_token, init.organizer, init.device_id, init.unique_serial, BuildConfig.VERSION_CODE)
                conf!!.proxyMode = token.startsWith("proxy=")
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
                e.printStackTrace();
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, R.string.setup_error_request).show()
                }
                return@doAsync
            } catch (e: SSLException) {
                e.printStackTrace();
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, R.string.setup_error_ssl).show()
                }
                return@doAsync
            } catch (e: IOException) {
                e.printStackTrace();
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, R.string.setup_error_io).show()
                }
                return@doAsync
            } catch (e: SetupServerErrorException) {
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, R.string.setup_error_server).show()
                }
            } catch (e: SetupBadResponseException) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, R.string.setup_error_response).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Sentry.capture(e)
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    resume()
                    alert(Appcompat, e.message ?: "Unknown error").show()
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
                alert(Appcompat, "") {
                    val view = layoutInflater.inflate(R.layout.dialog_setup_manual, null)
                    val inputUri = view.findViewById<EditText>(R.id.input_uri)
                    if (BuildConfig.APPLICATION_ID.contains("eu.pretix")) {
                        inputUri.setText("https://pretix.eu")
                    }
                    val inputToken = view.findViewById<EditText>(R.id.input_token)
                    customView = view
                    positiveButton(R.string.ok) {
                        it.dismiss()
                        initialize(inputUri.text.toString(), inputToken.text.toString())
                    }
                    negativeButton(android.R.string.cancel) {
                        it.cancel()
                    }
                }.show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
