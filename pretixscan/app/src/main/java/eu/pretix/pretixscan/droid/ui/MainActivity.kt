package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.snackbar.Snackbar
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AndroidSentryImplementation
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import io.sentry.Sentry
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.include_main_toolbar.*
import org.jetbrains.anko.*
import org.jetbrains.anko.appcompat.v7.Appcompat
import kotlin.apply


interface ReloadableActivity {
    fun reload()
}

class MainActivity : AppCompatActivity(), ReloadableActivity {
    private val REQ_EVENT = 1

    private lateinit var sm: SyncManager
    private lateinit var conf: AppConfig
    private val handler = Handler()

    override fun reload() {
        reloadSyncStatus()
    }

    fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            textView_status.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_red));
        } else {
            textView_status.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_green));
        }
        var text = ""
        val diff = System.currentTimeMillis() - conf.lastDownload
        if (conf.lastDownload == 0L) {
            text = getString(R.string.sync_status_never);
        } else if (diff > 24 * 3600 * 1000) {
            val days = (diff / (24 * 3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_days, days, days);
        } else if (diff > 3600 * 1000) {
            val hours = (diff / (3600 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_hours, hours, hours);
        } else if (diff > 60 * 1000) {
            val mins = (diff / (60 * 1000)).toInt()
            text = getResources().getQuantityString(R.plurals.sync_status_time_minutes, mins, mins);
        } else {
            text = getString(R.string.sync_status_now);
        }
        textView_status.setText(text)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            1337 -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, 1338)
                } else {
                    finish()
                }
                return
            }
            1338 -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                } else {
                    finish()
                }
                return
            }
            else -> {
                // Ignore all other requests.
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayUseLogoEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        conf = AppConfig(this)
        if (!conf.deviceRegistered) {
            registerDevice()
            return
        }
        setupApi()

        event.apply {
            setOnClickListener {
                selectEvent()
            }
        }

        /*button.setOnClickListener {
            syncNow()
        }*/
        checkPermission(Manifest.permission.CAMERA)
        if (conf.eventName == null || conf.eventSlug == null) {
            selectEvent()
        } else if (conf.lastDownload < 1) {
            syncNow()
        }
    }

    private fun setupApi() {
        if (event.text != conf.eventName) {
            event.text = conf.eventName
            (event.parent as View).forceLayout()
        }
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory())

        sm = SyncManager(
                conf,
                api,
                AndroidSentryImplementation(),
                (application as PretixScan).data,
                (application as PretixScan).fileStorage,
                60000L,
                5 * 60000L
        )
    }

    private fun selectEvent() {
        if (event != null && ViewCompat.isLaidOut(event)) {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this@MainActivity, event, "morph_transition")
            startActivityForResult(intentFor<EventSelectActivity>(), REQ_EVENT, options.toBundle())
        } else {
            startActivityForResult(intentFor<EventSelectActivity>(), REQ_EVENT)
        }
    }

    private fun snackbar(message: String) {
        Snackbar.make(findViewById(R.id.root_layout), message, Snackbar.LENGTH_LONG).show();
    }

    private fun snackbar(message: Int) {
        Snackbar.make(findViewById(R.id.root_layout), message, Snackbar.LENGTH_LONG).show();
    }

    private fun registerDevice() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK.or(Intent.FLAG_ACTIVITY_TASK_ON_HOME)
        startActivity(intent)
        finish()
    }

    private val syncRunnable = Runnable {
        doAsync {
            try {
                if (defaultSharedPreferences.getBoolean("pref_sync_auto", true)) {
                    val result = sm.sync(false)
                    if (result.isDataDownloaded) {
                        runOnUiThread {
                            reload()
                        }
                    } else if (result.isDataUploaded) {
                        reloadSyncStatus()
                    }
                }
                runOnUiThread {
                    scheduleSync()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    reload()
                }
            }
        }
    }

    fun scheduleSync() {
        handler.postDelayed(syncRunnable, 1000)
    }

    fun syncNow() {
        val dialog = indeterminateProgressDialog(message = R.string.progress_syncing)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        doAsync {
            try {
                sm.sync(true)
                runOnUiThread {
                    reload()
                    dialog.dismiss()
                    if (conf.lastFailedSync > 0) {
                        alert(Appcompat, conf.lastFailedSyncMsg).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (BuildConfig.SENTRY_DSN != null) {
                        Sentry.capture(e)
                    }
                    dialog.dismiss()
                    alert(Appcompat, e.message
                            ?: getString(R.string.error_unknown_exception)).show()
                }
            }
        }
    }

    override fun onResume() {
        reload()
        super.onResume()
        scheduleSync()
    }

    override fun onPause() {
        handler.removeCallbacks(syncRunnable)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_EVENT) {
            if (resultCode == RESULT_OK) {
                setupApi()
                syncNow()
                reload()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu to use in the action bar
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
