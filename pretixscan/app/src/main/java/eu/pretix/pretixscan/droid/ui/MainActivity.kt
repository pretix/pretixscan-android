package eu.pretix.pretixscan.droid.ui

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.annotation.TargetApi
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.LinearLayoutManager
import com.andrognito.pinlockview.IndicatorDots
import com.andrognito.pinlockview.PinLockListener
import com.andrognito.pinlockview.PinLockView
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.Result
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.CheckException
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.*
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.HardwareScanner
import eu.pretix.pretixscan.ScanReceiver
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
import java.text.SimpleDateFormat


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
    SUCCESS,
    SUCCESS_EXIT
}

class ViewDataHolder(private val ctx: Context) {
    val result_state = ObservableField<ResultState>()
    val search_state = ObservableField<ResultState>()
    val result_text = ObservableField<String>()
    val show_print = ObservableField<Boolean>()
    val detail1 = ObservableField<String>()
    val detail2 = ObservableField<String>()
    val detail3 = ObservableField<String>()
    val detail4 = ObservableField<String>()
    val detail5 = ObservableField<String>()
    val detail6 = ObservableField<String>()
    val attention = ObservableField<Boolean>()
    val hardwareScan = ObservableField<Boolean>()
    val kioskMode = ObservableField<Boolean>()
    val scanType = ObservableField<String>()
    val configDetails = ObservableField<String>()
    val isOffline = ObservableField<Boolean>()

    fun getColor(state: ResultState): Int {
        return ctx.resources.getColor(when (state) {
            ResultState.DIALOG, ResultState.LOADING -> R.color.pretix_brand_lightgrey
            ResultState.ERROR -> R.color.pretix_brand_red
            ResultState.WARNING -> R.color.pretix_brand_orange
            ResultState.SUCCESS, ResultState.SUCCESS_EXIT -> R.color.pretix_brand_green
        })
    }
}

class MainActivity : AppCompatActivity(), ReloadableActivity, ZXingScannerView.ResultHandler, MediaPlayer.OnCompletionListener {

    private val REQ_EVENT = 1
    private val REQ_CHECKINLIST = 2

    private lateinit var sm: SyncManager
    private lateinit var conf: AppConfig
    private val handler = Handler()
    private val hideHandler = Handler()
    private var card_state = ResultCardState.HIDDEN
    private var view_data = ViewDataHolder(this)
    private var mediaPlayers: MutableMap<Int, MediaPlayer> = mutableMapOf()

    private var lastScanTime: Long = 0
    private var lastScanCode: String = ""
    private var keyboardBuffer: String = ""
    private var dialog: QuestionsDialogInterface? = null
    private var pdialog: ProgressDialog? = null
    private val dataWedgeHelper = DataWedgeHelper(this)

    private var searchAdapter: SearchListAdapter? = null
    private var searchFilter = ""

    private var syncMessage = ""

    companion object {
        const val PERMISSIONS_REQUEST_WRITE_STORAGE = 1338
    }

    private val hardwareScanner = HardwareScanner(object : ScanReceiver {
        override fun scanResult(result: String) {
            lastScanTime = System.currentTimeMillis()
            lastScanCode = result
            handleScan(result, null, !conf.unpaidAsk)
        }
    })

    override fun reload() {
        reloadSyncStatus()

        var confdetails = ""
        if (!conf.eventSlug.isNullOrBlank()) {
            val event = (application as PretixScan).data.select(Event::class.java)
                    .where(Event.SLUG.eq(conf.eventSlug))
                    .get().firstOrNull()
            if (event != null) {
                confdetails += getString(R.string.debug_info_event, event.name)

                if (conf.subeventId != null && conf.subeventId!! > 0) {
                    val subevent = (application as PretixScan).data.select(SubEvent::class.java)
                            .where(SubEvent.SERVER_ID.eq(conf.subeventId))
                            .get().firstOrNull()
                    if (subevent != null) {
                        confdetails += "\n"
                        val df = SimpleDateFormat(getString(R.string.short_datetime_format))
                        confdetails += getString(R.string.debug_info_subevent, subevent.name, df.format(subevent.date_from))
                    }
                }

                if (conf.checkinListId > 0) {
                    val cl = (application as PretixScan).data.select(CheckInList::class.java)
                            .where(CheckInList.SERVER_ID.eq(conf.checkinListId))
                            .get().firstOrNull()
                    if (cl != null) {
                        confdetails += "\n"
                        confdetails += getString(R.string.debug_info_list, cl.name)
                    }
                }

                if (!conf.deviceKnownGateName.isNullOrBlank()) {
                    confdetails += "\n"
                    confdetails += getString(R.string.debug_info_gate, conf.deviceKnownGateName)
                }
            }
            if (!conf.kioskMode) {
                confdetails += "\n"
                confdetails += getString(R.string.debug_info_device, conf.deviceKnownName)
                confdetails += "\n"
                if (conf.proxyMode) {
                    confdetails += getString(R.string.checktype_proxy)
                } else if (conf.offlineMode) {
                    confdetails += getString(R.string.checktype_offline)
                } else {
                    confdetails += getString(R.string.checktype_online)
                }
            }

        }
        view_data.configDetails.set(confdetails)
        view_data.isOffline.set(conf.offlineMode)
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
                        lastScanCode = res.secret!!
                        hideSearchCard()
                        handleScan(res.secret!!, null, !conf.unpaidAsk)
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
            } catch (e: CheckException) {
                e.printStackTrace()
                runOnUiThread {
                    hideSearchCard()
                    toast(e.message ?: getString(R.string.error_unknown_exception))
                }
            } catch (e: Exception) {
                if (BuildConfig.SENTRY_DSN != null) {
                    Sentry.captureException(e)
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
        textView_status.visibility = if (conf.proxyMode) View.GONE else View.VISIBLE
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

    override fun onCompletion(p0: MediaPlayer?) {
        p0?.seekTo(0)
    }

    @SuppressWarnings("ResourceType")
    private fun buildMediaPlayer() {
        val resourceIds = listOf(R.raw.enter, R.raw.exit, R.raw.error, R.raw.beep)
        for (r in resourceIds) {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mediaPlayer.setOnCompletionListener(this)
            // mediaPlayer.setOnErrorListener(this)
            try {
                val file = resources.openRawResourceFd(r)
                try {
                    mediaPlayer.setDataSource(file.fileDescriptor, file.startOffset, file.length)
                } finally {
                    file.close();
                }
                mediaPlayer.setVolume(0.2f, 0.2f)
                mediaPlayer.prepare()
                mediaPlayers[r] = mediaPlayer
            } catch (ioe: IOException) {
            }
        }
    }


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conf = AppConfig(this)

        getRestrictions(this)
        val binding: ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        view_data.result_state.set(ResultState.ERROR)
        view_data.scanType.set(conf.scanType)
        view_data.hardwareScan.set(!conf.useCamera)
        binding.data = view_data

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayUseLogoEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        volumeControlStream = AudioManager.STREAM_MUSIC
        buildMediaPlayer()

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
        val api = PretixApi.fromConfig(conf, AndroidHttpClientFactory(application as PretixScan))

        sm = SyncManager(
                conf,
                api,
                AndroidSentryImplementation(),
                (application as PretixScan).data,
                (application as PretixScan).fileStorage,
                60000L,
                5 * 60000L,
                if (conf.syncOrders) SyncManager.Profile.PRETIXSCAN else SyncManager.Profile.PRETIXSCAN_ONLINE,
                conf.printBadges,
                BuildConfig.VERSION_CODE,
                Build.BRAND,
                Build.MODEL,
                "pretixSCAN",
                BuildConfig.VERSION_NAME,
                null
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
            val intent = intentFor<EventSelectActivity>()
            startWithPIN(intent, "statistics", REQ_EVENT, options.toBundle())
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
            if (dialog != null && dialog!!.isShowing()) {
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
                    val result = sm.sync(false, conf.checkinListId) {
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
            } catch (e: SyncManager.EventSwitchRequested) {
                runOnUiThread {
                    conf.eventSlug = e.eventSlug
                    conf.subeventId = e.subeventId
                    conf.eventName = e.eventName
                    conf.checkinListId = e.checkinlistId
                    setupApi()
                    syncNow(!(e.checkinlistId > 0))
                    reload()
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
        if (isDestroyed) return
        syncMessage = ""
        pdialog = indeterminateProgressDialog(title = if (selectList) R.string.progress_syncing_first else R.string.progress_syncing, message = if (selectList) R.string.progress_syncing_first else R.string.progress_syncing)
        (pdialog as ProgressDialog).setCanceledOnTouchOutside(false)
        (pdialog as ProgressDialog).setCancelable(false)
        doAsync {
            if (!(application as PretixScan).syncLock.tryLock()) {
                runOnUiThread {
                    alert(Appcompat, getString(R.string.error_sync_in_background)).show()
                    (pdialog as ProgressDialog).dismiss()
                }
                return@doAsync
            }
            try {
                if (selectList) {
                    sm.syncMinimalEventSet { current_action ->
                        runOnUiThread {
                            if (isDestroyed) {
                                return@runOnUiThread
                            }
                            reloadSyncStatus()
                            syncMessage = current_action
                            (pdialog as ProgressDialog).setMessage(current_action)
                        }
                    }
                } else {
                    sm.sync(true, conf.checkinListId) { current_action ->
                        runOnUiThread {
                            if (isDestroyed) {
                                return@runOnUiThread
                            }
                            reloadSyncStatus()
                            syncMessage = current_action
                            (pdialog as ProgressDialog).setMessage(current_action)
                        }
                    }
                }
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    reload()
                    if (selectList) {
                        selectCheckInList()
                    }
                    (pdialog as ProgressDialog).dismiss()
                    if (conf.lastFailedSync > 0) {
                        alert(Appcompat, conf.lastFailedSyncMsg).show()
                    }
                }
            } catch (e: SyncManager.EventSwitchRequested) {
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    (pdialog as ProgressDialog).dismiss()
                    conf.eventSlug = e.eventSlug
                    conf.subeventId = e.subeventId
                    conf.eventName = e.eventName
                    conf.checkinListId = e.checkinlistId
                    setupApi()
                    syncNow(!(e.checkinlistId > 0))
                    reload()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (isDestroyed) {
                        return@runOnUiThread
                    }
                    if (BuildConfig.SENTRY_DSN != null) {
                        Sentry.captureException(e)
                    }
                    (pdialog as ProgressDialog).dismiss()
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
        setupApi()
        getRestrictions(this)

        view_data.kioskMode.set(conf.kioskMode)
        if (conf.kioskMode) {
            supportActionBar?.hide()
            window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        } else {
            supportActionBar?.show()
        }

        hardwareScanner.start(this)

        if (conf.useCamera) {
            scanner_view.setResultHandler(this)
            scanner_view.startCamera()
        }
        view_data.scanType.set(conf.scanType)
        view_data.hardwareScan.set(!conf.useCamera)
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
        view_data.detail4.set(null)
        view_data.detail5.set(null)
        view_data.detail6.set(null)
        view_data.attention.set(false)
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
            card_result.animate().start()
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
        hardwareScanner.stop(this)
    }

    fun handleScan(result: String, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false) {
        if (conf.kioskMode && conf.requiresPin("settings") && conf.verifyPin(result)) {
            supportActionBar?.show()
            return
        }
        showLoadingCard()
        hideSearchCard()
        if (answers == null && !ignore_unpaid && !conf.offlineMode && conf.sounds) {
            mediaPlayers[R.raw.beep]?.start()
        }
        doAsync {
            var checkResult: TicketCheckProvider.CheckResult? = null
            if (Regex("[0-9A-Za-z+/=]+").matches(result)) {
                val provider = (application as PretixScan).getCheckProvider(conf)
                try {
                    checkResult = provider.check(result, answers, ignore_unpaid, conf.printBadges, when (conf.scanType) {
                        "exit" -> TicketCheckProvider.CheckInType.EXIT
                        else -> TicketCheckProvider.CheckInType.ENTRY
                    })
                } catch (e: Exception) {
                    if (BuildConfig.SENTRY_DSN != null) {
                        Sentry.captureException(e)
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

    fun showQuestionsDialog(res: TicketCheckProvider.CheckResult, secret: String, ignore_unpaid: Boolean,
                            retryHandler: ((String, MutableList<Answer>, Boolean) -> Unit)): QuestionsDialogInterface {
        val questions = res.requiredAnswers!!.map { it.question }
        val values = mutableMapOf<QuestionLike, String>()
        res.requiredAnswers!!.forEach {
            if (!it.currentValue.isNullOrBlank()) {
                values[it.question] = it.currentValue!!
            }
        }
        return eu.pretix.libpretixui.android.questions.showQuestionsDialog(this, questions, values, null) { answers ->
            retryHandler(secret, answers, ignore_unpaid)
        }
    }

    fun displayScanResult(result: TicketCheckProvider.CheckResult, answers: MutableList<Answer>?, ignore_unpaid: Boolean = false) {
        if (conf.sounds)
            when (result.type) {
                TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                    TicketCheckProvider.CheckInType.ENTRY -> mediaPlayers[R.raw.enter]?.start()
                    TicketCheckProvider.CheckInType.EXIT -> mediaPlayers[R.raw.exit]?.start()
                }
                TicketCheckProvider.CheckResult.Type.INVALID -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.ERROR -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.UNPAID -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.CANCELED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.PRODUCT -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.RULES -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.REVOKED -> mediaPlayers[R.raw.error]?.start()
                TicketCheckProvider.CheckResult.Type.USED -> mediaPlayers[R.raw.error]?.start()
                else -> {
                }
            }

        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 30000)
        if (result.type == TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED) {
            view_data.result_state.set(ResultState.DIALOG)
            dialog = showQuestionsDialog(result, lastScanCode, ignore_unpaid) { secret, answers, ignore_unpaid ->
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener(DialogInterface.OnCancelListener { hideCard() })
            return
        }
        if (result.type == TicketCheckProvider.CheckResult.Type.UNPAID && result.isCheckinAllowed) {
            view_data.result_state.set(ResultState.DIALOG)
            dialog = showUnpaidDialog(this, result, lastScanCode, answers) { secret, answers, ignore_unpaid ->
                handleScan(secret, answers, ignore_unpaid)
            }
            dialog!!.setOnCancelListener(DialogInterface.OnCancelListener { hideCard() })
            return
        }
        if (result.message == null) {
            result.message = when (result.type!!) {
                TicketCheckProvider.CheckResult.Type.INVALID -> getString(R.string.scan_result_invalid)
                TicketCheckProvider.CheckResult.Type.VALID -> when (result.scanType) {
                    TicketCheckProvider.CheckInType.EXIT -> getString(R.string.scan_result_exit)
                    TicketCheckProvider.CheckInType.ENTRY -> getString(R.string.scan_result_valid)
                }
                TicketCheckProvider.CheckResult.Type.RULES -> getString(R.string.scan_result_rules)
                TicketCheckProvider.CheckResult.Type.REVOKED -> getString(R.string.scan_result_revoked)
                TicketCheckProvider.CheckResult.Type.UNPAID -> getString(R.string.scan_result_unpaid)
                TicketCheckProvider.CheckResult.Type.CANCELED -> getString(R.string.scan_result_canceled)
                TicketCheckProvider.CheckResult.Type.PRODUCT -> getString(R.string.scan_result_product)
                else -> null
            }
        }
        view_data.result_text.set(result.message)
        view_data.result_state.set(when (result.type!!) {
            TicketCheckProvider.CheckResult.Type.INVALID -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.VALID -> {
                when (result.scanType) {
                    TicketCheckProvider.CheckInType.EXIT -> ResultState.SUCCESS_EXIT
                    TicketCheckProvider.CheckInType.ENTRY -> ResultState.SUCCESS
                }
            }
            TicketCheckProvider.CheckResult.Type.USED -> ResultState.WARNING
            TicketCheckProvider.CheckResult.Type.ERROR -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.RULES -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.REVOKED -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.UNPAID -> ResultState.ERROR
            TicketCheckProvider.CheckResult.Type.CANCELED -> ResultState.ERROR
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
        if (result.seat != null) {
            view_data.detail4.set(result.seat)
        } else {
            view_data.detail4.set(null)
        }
        if (result.firstScanned != null) {
            val df = SimpleDateFormat(getString(R.string.short_datetime_format))
            view_data.detail6.set(getString(R.string.first_scanned, df.format(result.firstScanned)))
        } else {
            view_data.detail6.set(null)
        }
        if (!result.reasonExplanation.isNullOrBlank()) {
            view_data.detail5.set(result.reasonExplanation)
        } else {
            view_data.detail5.set(null)
        }

        view_data.attention.set(result.isRequireAttention)

        if (result.scanType != TicketCheckProvider.CheckInType.EXIT) {
            if (result.position != null && result.type == TicketCheckProvider.CheckResult.Type.VALID && conf.printBadges && conf.autoPrintBadges) {
                printBadge(this@MainActivity, application as PretixScan, result.position!!, conf.eventSlug!!, null)
            }
            if (result.position != null && conf.printBadges) {
                view_data.show_print.set(getBadgeLayout(application as PretixScan, result.position!!, conf.eventSlug!!) != null)
                ibPrint.setOnClickListener {
                    printBadge(this@MainActivity, application as PretixScan, result.position!!, conf.eventSlug!!, null)
                }
            } else {
                view_data.show_print.set(false)
            }
        } else {
            view_data.show_print.set(false)
        }

        card_result.clearAnimation()
        if (result.isRequireAttention) {
            card_result.rotation = 0f
            ObjectAnimator.ofFloat(card_result, "rotationY", 0f, 25f, 0f, -25f, 0f, 25f, 0f, -25f, 0f).apply {
                duration = 1500
                start()
            }
        }

    }

    override fun handleResult(rawResult: Result) {
        scanner_view.resumeCameraPreview(this@MainActivity)

        if ((dialog != null && dialog!!.isShowing()) || view_data.result_state.get() == ResultState.LOADING) {
            return
        }

        val s = rawResult.text
        if (s == lastScanCode && System.currentTimeMillis() - lastScanTime < 5000) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        lastScanCode = s
        handleScan(s, null, !conf.unpaidAsk)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || (currentFocus is TextView && currentFocus !is AppCompatButton)) {
            return super.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyboardBuffer.isEmpty()) {
                    false
                }
                handleScan(keyboardBuffer, null, !conf.unpaidAsk)
                keyboardBuffer = ""
                true
            }
            else -> {
                val codepoint = event.keyCharacterMap.get(event.keyCode, 0)
                if (codepoint > 0) {
                    keyboardBuffer += codepoint.toChar()
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_EVENT) {
            if (resultCode == RESULT_OK) {
                setupApi()
                syncNow(true)
                reload()
            }
        } else if (requestCode == REQ_CHECKINLIST) {
            if (resultCode == RESULT_OK) {
                reload()
                scheduleSync()
            }
        } else if (dialog?.handleActivityResult(requestCode, resultCode, data) == true) {
            return
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu to use in the action bar
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)

        menu.findItem(R.id.action_scantype).title = if (conf.scanType == "exit") {
            getString(R.string.action_label_scantype_entry)
        } else {
            getString(R.string.action_label_scantype_exit)
        }
        menu.findItem(R.id.action_scantype).isVisible = conf.knownPretixVersion >= 30090001000
        menu.findItem(R.id.action_stats).isVisible = !conf.offlineMode || conf.syncOrders

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as SearchView
        searchItem.isVisible = !conf.searchDisabled
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

    fun pinProtect(key: String, valid: ((pin: String) -> Unit)) {
        if (!conf.requiresPin(key)) {
            valid("")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_pin, null)
        val dialog = AlertDialog.Builder(this)
                .setView(view)
                .create()
        dialog.setOnShowListener {
            val mPinLockListener: PinLockListener = object : PinLockListener {
                override fun onComplete(pin: String) {
                    this.onPinChange(pin.length, pin)
                }

                override fun onEmpty() {
                }

                override fun onPinChange(pinLength: Int, intermediatePin: String) {
                    if (conf.verifyPin(intermediatePin)) {
                        dialog.dismiss()
                        valid(intermediatePin)
                    }
                }
            }

            val lockView = view.findViewById(R.id.pin_lock_view) as PinLockView
            lockView.pinLength = conf.getPinLength()
            lockView.setPinLockListener(mPinLockListener)
            val idots = view.findViewById(R.id.indicator_dots) as IndicatorDots
            idots.pinLength = conf.getPinLength()
            lockView.attachIndicatorDots(idots);
        }
        dialog.show()
    }

    fun startWithPIN(intent: Intent, key: String, resultCode: Int? = null, bundle: Bundle? = null) {
        pinProtect(key) { pin ->
            intent.putExtra("pin", pin)
            if (resultCode != null) {
                startActivityForResult(intent, resultCode, bundle)
            } else {
                startActivity(intent)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startWithPIN(intent, "settings")
                return true
            }
            R.id.action_stats -> {
                val intent = Intent(this@MainActivity, EventinfoActivity::class.java)
                startWithPIN(intent, "statistics")
                return true
            }
            R.id.action_scantype -> {
                pinProtect("switch_mode") {
                    if (conf.scanType == "entry") {
                        conf.scanType = "exit"
                        item.title = getString(R.string.action_label_scantype_entry)
                    } else {
                        conf.scanType = "entry"
                        item.title = getString(R.string.action_label_scantype_exit)
                    }
                    view_data.scanType.set(conf.scanType)
                }
            }
            R.id.action_sync -> {
                syncNow()
            }
        }
        return super.onOptionsItemSelected(item)
    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    fun getRestrictions(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val myRestrictionsMgr = ctx.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager?
                ?: return
        val restrictions = myRestrictionsMgr.applicationRestrictions

        for (key in restrictions.keySet()) {
            defaultSharedPreferences.edit().putBoolean(key, restrictions.getBoolean(key)).apply()
        }
    }
}
