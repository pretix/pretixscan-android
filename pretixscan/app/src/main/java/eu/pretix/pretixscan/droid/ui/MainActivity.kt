package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.Result
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.pretixscan.droid.*
import eu.pretix.pretixscan.droid.databinding.ActivityMainBinding
import eu.pretix.pretixscan.droid.ui.info.EventinfoActivity
import io.sentry.Sentry
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.include_main_toolbar.*
import me.dm7.barcodescanner.zxing.ZXingScannerView
import org.jetbrains.anko.*
import org.jetbrains.anko.appcompat.v7.Appcompat
import java.io.IOException


interface ReloadableActivity {
    fun reload()
}

enum class ResultCardState {
    HIDDEN,
    SHOWN
}

enum class ResultState {
    LOADING,
    ERROR,
    DIALOG,
    WARNING,
    SUCCESS
}

class ViewDataHolder(private val ctx: Context) {
    val result_state = ObservableField<ResultState>()
    val search_state = ObservableField<ResultState>()
    val result_text = ObservableField<String>()
    val show_print = ObservableField<Boolean>()
    val detail1 = ObservableField<String>()
    val detail2 = ObservableField<String>()
    val detail3 = ObservableField<String>()

    fun getColor(state: ResultState): Int {
        return ctx.resources.getColor(when (state) {
            ResultState.DIALOG, ResultState.LOADING -> R.color.pretix_brand_lightgrey
            ResultState.ERROR -> R.color.pretix_brand_red
            ResultState.WARNING -> R.color.pretix_brand_orange
            ResultState.SUCCESS -> R.color.pretix_brand_green
        })
    }
}

class MainActivity : AppCompatActivity(), ReloadableActivity, ZXingScannerView.ResultHandler {

    private val REQ_EVENT = 1
    private val REQ_CHECKINLIST = 2

    private lateinit var sm: SyncManager
    private lateinit var conf: AppConfig
    private val handler = Handler()
    private val hideHandler = Handler()
    private var card_state = ResultCardState.HIDDEN
    private var view_data = ViewDataHolder(this)

    private var lastScanTime: Long = 0
    private var lastScanCode: String = ""
    private var dialog: Dialog? = null
    private val dataWedgeHelper = DataWedgeHelper(this)

    private var searchAdapter: SearchListAdapter? = null
    private var searchFilter = ""

    private var syncMessage = ""

    companion object {
        const val PERMISSIONS_REQUEST_WRITE_STORAGE = 1338
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra("com.symbol.datawedge.data_string")) {
                // Zebra DataWedge
                lastScanTime = System.currentTimeMillis()
                lastScanCode = intent.getStringExtra("com.symbol.datawedge.data_string")
                handleScan(intent.getStringExtra("com.symbol.datawedge.data_string"), null, false)
            } else if (intent.hasExtra("barocode")) {
                // Intent receiver for LECOM-manufactured hardware scanners
                val barcode = intent?.getByteArrayExtra("barocode") // sic!
                val barocodelen = intent?.getIntExtra("length", 0)
                val barcodeStr = String(barcode, 0, barocodelen)
                lastScanTime = System.currentTimeMillis()
                lastScanCode = barcodeStr
                handleScan(barcodeStr, null, false)
            }
        }
    }

    override fun reload() {
        reloadSyncStatus()
    }

    private fun setSearchFilter(f: String) {
        card_search.visibility = View.VISIBLE
        view_data.search_state.set(ResultState.LOADING)

        searchFilter = f
        doAsync {
            val provider = (application as PretixScan).getCheckProvider(conf)
            try {
                val sr = provider.search(f, 1)
                if (f != searchFilter) {
                    // we lost a race! Abort this.
                    return@doAsync
                }
                searchAdapter = SearchListAdapter(sr, object : SearchResultClickedInterface {
                    override fun onSearchResultClicked(res: TicketCheckProvider.SearchResult) {
                        lastScanTime = System.currentTimeMillis()
                        lastScanCode = res.secret
                        hideSearchCard()
                        handleScan(res.secret, null, false)
                    }
                })
                runOnUiThread {
                    recyclerView_search.adapter = searchAdapter
                    if (sr.size == 0) {
                        view_data.search_state.set(ResultState.WARNING)
                    } else {
                        view_data.search_state.set(ResultState.SUCCESS)
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.SENTRY_DSN != null) {
                    Sentry.capture(e)
                } else {
                    e.printStackTrace()
                }
                runOnUiThread {
                    hideSearchCard()
                    toast(R.string.error_unknown_exception)
                }
            }
        }
    }

    fun reloadSyncStatus() {
        if (conf.lastFailedSync > conf.lastSync || System.currentTimeMillis() - conf.lastDownload > 5 * 60 * 1000) {
            textView_status.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_red));
        } else {
            textView_status.setTextColor(ContextCompat.getColor(this, R.color.pretix_brand_green));
        }
        var text = ""
        val diff = System.currentTimeMillis() - conf.lastDownload
        if ((application as PretixScan).syncLock.isLocked) {
            if (syncMessage != "") {
                text = syncMessage
            } else {
                text = getString(R.string.sync_status_progress);
            }
        } else if (conf.lastDownload == 0L) {
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
                    checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, PERMISSIONS_REQUEST_WRITE_STORAGE)
                } else {
                    finish()
                }
                return
            }
            PERMISSIONS_REQUEST_WRITE_STORAGE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    try {
                        if (dataWedgeHelper.isInstalled) {
                            dataWedgeHelper.install()
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
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

    private fun setUpEventListeners() {
        event.setOnClickListener {
            selectEvent()
        }

        fab_focus.setOnClickListener {
            conf.scanFocus = !conf.scanFocus
            reloadCameraState()
        }

        fab_flash.setOnClickListener {
            conf.scanFlash = !conf.scanFlash
            reloadCameraState()
        }

        card_result.setOnTouchListener(object : OnSwipeTouchListener(this) {
            override fun onSwipeLeft() {
                hideHandler.removeCallbacks(hideRunnable)
                card_state = ResultCardState.HIDDEN
                card_result.clearAnimation()
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                card_result.animate().translationX(-(displayMetrics.widthPixels + card_result.width) / 2f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(0f).start()
                hideHandler.postDelayed(hideRunnable, 250)
            }

            override fun onSwipeRight() {
                hideHandler.removeCallbacks(hideRunnable)
                card_state = ResultCardState.HIDDEN
                card_result.clearAnimation()
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                card_result.animate().translationX((displayMetrics.widthPixels + card_result.width) / 2f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(0f).start()
                hideHandler.postDelayed(hideRunnable, 250)
            }
        })
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding: ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        view_data.result_state.set(ResultState.ERROR)
        binding.data = view_data

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayUseLogoEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        conf = AppConfig(this)
        if (!conf.deviceRegistered) {
            registerDevice()
            return
        }
        setupApi()
        setUpEventListeners()

        if (conf.eventName == null || conf.eventSlug == null) {
            selectEvent()
        } else if (conf.checkinListId == 0L) {
            selectCheckInList()
        } else if (conf.lastDownload < 1) {
            syncNow()
        }
        scheduleSync()
        checkPermission(Manifest.permission.CAMERA)

        hideCard()
        hideSearchCard()
        card_result.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        if (dataWedgeHelper.isInstalled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERMISSIONS_REQUEST_WRITE_STORAGE);
            } else {
                try {
                    dataWedgeHelper.install()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        recyclerView_search.layoutManager = LinearLayoutManager(this)
        recyclerView_search.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(recyclerView_search.context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL))
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
                5 * 60000L,
                false
        )
    }

    private fun selectCheckInList() {
        if (event != null && ViewCompat.isLaidOut(event)) {
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this@MainActivity, event, "morph_transition")
            startActivityForResult(intentFor<CheckInListSelectActivity>(), REQ_CHECKINLIST, options.toBundle())
        } else {
            startActivityForResult(intentFor<CheckInListSelectActivity>(), REQ_CHECKINLIST)
        }
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

    private val hideRunnable = Runnable {
        runOnUiThread {
            if (dialog != null && dialog!!.isShowing) {
                return@runOnUiThread
            }
            hideCard()
        }
    }

    private val syncRunnable = Runnable {
        syncMessage = ""
        doAsync {
            if (!(application as PretixScan).syncLock.tryLock()) {
                runOnUiThread {
                    reloadSyncStatus()
                }
                scheduleSync()
                return@doAsync
            }
            try {
                if (defaultSharedPreferences.getBoolean("pref_sync_auto", true)) {
                    val result = sm.sync(false) {
                        runOnUiThread {
                            syncMessage = it
                            reloadSyncStatus()
                        }
                    }
                    if (result.isDataDownloaded) {
                        runOnUiThread {
                            reload()
                        }
                    }
                }
                runOnUiThread {
                    reloadSyncStatus()
                    scheduleSync()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    reload()
                }
            } finally {
                (application as PretixScan).syncLock.unlock()
                runOnUiThread {
                    reloadSyncStatus()
                }
            }
        }
    }

    fun scheduleSync() {
        handler.postDelayed(syncRunnable, 1000)
    }

    fun syncNow(selectList: Boolean = false) {
        syncMessage = ""
        dialog = indeterminateProgressDialog(title = R.string.progress_syncing, message = R.string.progress_syncing)
        (dialog as ProgressDialog).setCanceledOnTouchOutside(false)
        (dialog as ProgressDialog).setCancelable(false)
        doAsync {
            if (!(application as PretixScan).syncLock.tryLock()) {
                runOnUiThread {
                    alert(Appcompat, getString(R.string.error_sync_in_background)).show()
                    (dialog as ProgressDialog).dismiss()
                }
                return@doAsync
            }
            try {
                sm.sync(true) { current_action ->
                    runOnUiThread {
                        reloadSyncStatus()
                        syncMessage = current_action
                        (dialog as ProgressDialog).setMessage(current_action)
                    }
                }
                runOnUiThread {
                    reload()
                    if (selectList) {
                        selectCheckInList()
                    }
                    (dialog as ProgressDialog).dismiss()
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
                    (dialog as ProgressDialog).dismiss()
                    alert(Appcompat, e.message
                            ?: getString(R.string.error_unknown_exception)).show()
                }
            } finally {
                (application as PretixScan).syncLock.unlock()
            }
        }
    }

    override fun onResume() {
        reload()
        super.onResume()

        val filter = IntentFilter()
        filter.addAction("scan.rcv.message")
        filter.addAction("eu.pretix.SCAN")
        registerReceiver(scanReceiver, filter)

        if (conf.useCamera) {
            scanner_view.setResultHandler(this)
            scanner_view.startCamera()
        }
        reloadCameraState()
    }

    fun hideSearchCard() {
        card_search.visibility = View.GONE
    }

    fun hideCard() {
        card_state = ResultCardState.HIDDEN
        card_result.clearAnimation()
        card_result.visibility = View.GONE
        view_data.result_state.set(ResultState.ERROR)
        view_data.result_text.set(null)
    }

    fun showLoadingCard() {
        hideHandler.removeCallbacks(hideRunnable)
        card_result.clearAnimation()
        view_data.result_state.set(ResultState.LOADING)
        view_data.result_text.set(null)
        view_data.detail1.set(null)
        view_data.detail2.set(null)
        view_data.detail3.set(null)
        if (card_state == ResultCardState.HIDDEN) {
            card_state = ResultCardState.SHOWN
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            card_result.translationX = (displayMetrics.widthPixels + card_result.width) / 2f
            card_result.alpha = 0f
            card_result.visibility = View.VISIBLE
            card_result.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).alpha(1f).start()
        } else {
            // bounce
            card_result.alpha = 1f
            card_result.translationX = 1f
            ObjectAnimator.ofFloat(card_result, "translationX", 0f, 50f, -50f, 0f).apply {
                duration = 250
                interpolator = BounceInterpolator()
                start()
            }
            card_result.animate().startDelay
        }
    }

    fun reloadCameraState() {
        try {
            scanner_view.flash = conf.scanFlash
            if (conf.scanFlash) {
                fab_flash.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.pretix_brand_green))
            } else {
                fab_flash.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.fab_disable))
            }
            scanner_view.setAutoFocus(conf.scanFocus)
            if (conf.scanFocus) {
                fab_focus.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.pretix_brand_green))
            } else {
                fab_focus.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.fab_disable))
            }
            if (conf.useCamera) {
                fab_focus.show()
                fab_flash.show()
            } else {
                fab_focus.hide()
                fab_flash.hide()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(syncRunnable)
        super.onPause()
        if (conf.useCamera) {
            scanner_view.stopCamera()
        }
        unregisterReceiver(scanReceiver);
    }

    fun handleScan(result: String, answers: MutableList<TicketCheckProvider.Answer>?, ignore_unpaid: Boolean = false) {
        showLoadingCard()
        hideSearchCard()
        doAsync {
            var checkResult: TicketCheckProvider.CheckResult? = null
            if (Regex("[0-9A-Za-z]+").matches(result)) {
                val provider = (application as PretixScan).getCheckProvider(conf)
                try {
                    checkResult = provider.check(result, answers, ignore_unpaid, conf.printBadges)
                } catch (e: Exception) {
                    if (BuildConfig.SENTRY_DSN != null) {
                        Sentry.capture(e)
                    } else {
                        e.printStackTrace()
                    }
                    checkResult = TicketCheckProvider.CheckResult(TicketCheckProvider.CheckResult.Type.INVALID, getString(R.string.error_unknown_exception))
                }
            } else {
                checkResult = TicketCheckProvider.CheckResult(TicketCheckProvider.CheckResult.Type.INVALID, getString(R.string.scan_result_invalid))
            }
            runOnUiThread {
                displayScanResult(checkResult!!, answers, ignore_unpaid)
            }
        }
    }

    fun displayScanResult(result: TicketCheckProvider.CheckResult, answers: MutableList<TicketCheckProvider.Answer>?, ignore_unpaid: Boolean = false) {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 30000)
        if (result.type == TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED) {
            view_data.result_state.set(ResultState.DIALOG)
            dialog = showQuestionsDialog(this, result, lastScanCode, ignore_unpaid) { secret, answers, ignore_unpaid ->
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener {
                hideCard()
            }
            return
        }
        if (result.type == TicketCheckProvider.CheckResult.Type.UNPAID && result.isCheckinAllowed) {
            view_data.result_state.set(ResultState.DIALOG)
            dialog = showUnpaidDialog(this, result, lastScanCode, answers) { secret, answers, ignore_unpaid ->
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener {
                hideCard()
            }
            return
        }
        if (result.message == null) {
            result.message = when (result.type) {
                TicketCheckProvider.CheckResult.Type.INVALID -> getString(R.string.scan_result_invalid)
                TicketCheckProvider.CheckResult.Type.VALID -> getString(R.string.scan_result_valid)
                TicketCheckProvider.CheckResult.Type.USED -> getString(R.string.scan_result_used)
                TicketCheckProvider.CheckResult.Type.UNPAID -> getString(R.string.scan_result_unpaid)
                TicketCheckProvider.CheckResult.Type.PRODUCT -> getString(R.string.scan_result_product)
                else -> null
            }
        }
        view_data.result_text.set(result.message)
        view_data.result_state.set(when (result.type) {
            TicketCheckProvider.CheckResult.Type.INVALID -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.VALID -> ResultState.SUCCESS
            TicketCheckProvider.CheckResult.Type.USED -> ResultState.WARNING
            TicketCheckProvider.CheckResult.Type.ERROR -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.UNPAID -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.PRODUCT -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> ResultState.ERROR
        })
        if (result.ticket != null) {
            if (result.variation != null) {
                view_data.detail1.set(result.ticket + " – " + result.variation)
            } else {
                view_data.detail1.set(result.ticket)
            }
        } else {
            view_data.detail1.set(null)
        }
        if (result.orderCode != null) {
            view_data.detail2.set(result.orderCode)
        } else {
            view_data.detail2.set(null)
        }
        if (result.attendee_name != null) {
            view_data.detail3.set(result.attendee_name)
        } else {
            view_data.detail3.set(null)
        }
        if (result?.position != null && result.type == TicketCheckProvider.CheckResult.Type.VALID && conf.printBadges && conf.autoPrintBadges) {
            printBadge(this@MainActivity, application as PretixScan, result.position, null)
        }
        if (result?.position != null && conf.printBadges) {
            view_data.show_print.set(getBadgeLayout(application as PretixScan, result.position) != null)
            ibPrint.setOnClickListener {
                printBadge(this@MainActivity, application as PretixScan, result.position, null)
            }
        } else {
            view_data.show_print.set(false)
        }
    }

    override fun handleResult(rawResult: Result) {
        scanner_view.resumeCameraPreview(this@MainActivity)

        if ((dialog != null && dialog!!.isShowing) || view_data.result_state.get() == ResultState.LOADING) {
            return
        }

        val s = rawResult.text
        if (s == lastScanCode && System.currentTimeMillis() - lastScanTime < 5000) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        lastScanCode = s
        handleScan(s, null, false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_EVENT) {
            if (resultCode == RESULT_OK) {
                setupApi()
                syncNow(true)
                reload()
            }
        } else if (resultCode == REQ_CHECKINLIST) {
            if (resultCode == RESULT_OK) {
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

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (query.isEmpty()) {
                    hideSearchCard()
                } else {
                    setSearchFilter(query)
                }
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (newText.isEmpty()) {
                    hideSearchCard()
                } else {
                    setSearchFilter(newText)
                }
                return true
            }
        })
        searchView.setOnCloseListener {
            hideSearchCard()
            return@setOnCloseListener true
        }

        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
                return true
            }
            R.id.action_stats -> {
                val intent = Intent(this@MainActivity, EventinfoActivity::class.java)
                startActivity(intent)
                return true
            }
            R.id.action_sync -> {
                syncNow()
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
